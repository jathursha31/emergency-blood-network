package com.blooddonor.security;

import com.blooddonor.entity.AuditEvent;
import com.blooddonor.entity.RefreshToken;
import com.blooddonor.entity.UserAccount;
import com.blooddonor.exception.InvalidRefreshTokenException;
import com.blooddonor.repository.RefreshTokenRepository;
import com.blooddonor.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens: random 256-bit strings, stored only as SHA-256 hashes.
 * <ul>
 *   <li>Rotation: every refresh revokes the presented token and issues a new one in the same family.</li>
 *   <li>Reuse detection: presenting an already-rotated token means it was copied, so the whole family
 *       (every session that came from that login) is revoked.</li>
 *   <li>Logout revokes one token; "log out everywhere" revokes all of an account's tokens.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final UserAccountRepository accounts;
    private final SecurityProperties props;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, UserAccountRepository accounts,
                               SecurityProperties props, Clock clock, ApplicationEventPublisher events,
                               AuditService audit) {
        this.audit = audit;
        this.tokens = tokens;
        this.accounts = accounts;
        this.props = props;
        this.clock = clock;
        this.events = events;
    }

    /** New session after a successful login or sign-up. Returns the raw token for the cookie and the session id. */
    @Transactional
    public Issued issue(String username, String userAgent) {
        UserAccount account = accounts.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("No account " + username));
        String sessionId = UUID.randomUUID().toString();
        return new Issued(create(account, sessionId, userAgent), sessionId);
    }

    /**
     * Swap a refresh token for a new one. Runs in its own transaction and keeps revocations
     * even when it throws, so a detected reuse really logs the thief out.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotated rotate(String raw, String userAgent) {
        if (raw == null || raw.isBlank()) throw new InvalidRefreshTokenException("Not logged in");
        RefreshToken current = tokens.findByTokenHash(hash(raw))
                .orElseThrow(() -> new InvalidRefreshTokenException("Session not found. Please log in again."));

        if (current.getRevokedAt() != null) {
            if ("ROTATED".equals(current.getRevokedReason())) {
                int n = tokens.revokeFamily(current.getFamily(), now(), "REUSE_DETECTED");
                log.warn("Refresh token reuse for '{}': revoked {} session token(s) of that login",
                        current.getAccount().getUsername(), n);
                events.publishEvent(new SessionEvents.SessionEnded(current.getAccount().getUsername(), current.getFamily()));
                audit.record(null, AuditEvent.Action.TOKEN_REUSE_DETECTED, current.getAccount().getUsername(),
                        "old refresh token replayed; " + n + " session token(s) revoked");
                throw new InvalidRefreshTokenException("This session was used somewhere else and has been closed. Please log in again.");
            }
            throw new InvalidRefreshTokenException("You have been logged out. Please log in again.");
        }
        if (now().isAfter(current.getExpiresAt())) {
            throw new InvalidRefreshTokenException("Your session has expired. Please log in again.");
        }
        UserAccount account = current.getAccount();
        if (!account.isEnabled()) {
            revokeAllFor(account);
            throw new InvalidRefreshTokenException("This account is disabled.");
        }

        current.setRevokedAt(now());
        current.setRevokedReason("ROTATED");
        String next = create(account, current.getFamily(), userAgent);
        return new Rotated(account, next, current.getFamily());
    }

    /** Logout on this device. Unknown or already revoked tokens are ignored. */
    @Transactional
    public void revoke(String raw) {
        if (raw == null || raw.isBlank()) return;
        tokens.findByTokenHash(hash(raw)).filter(t -> t.getRevokedAt() == null).ifPresent(t -> {
            t.setRevokedAt(now());
            t.setRevokedReason("LOGOUT");
            events.publishEvent(new SessionEvents.SessionEnded(t.getAccount().getUsername(), t.getFamily()));
            audit.record(t.getAccount().getUsername(), AuditEvent.Action.LOGOUT, t.getAccount().getUsername(), null);
        });
    }

    /**
     * Logout on every device: revokes every refresh token and bumps the account's token version,
     * which makes every access token already handed out invalid at once. Returns how many sessions were closed.
     */
    @Transactional
    public int revokeAll(String username) {
        return accounts.findByUsername(username).map(a -> {
            int n = revokeAllFor(a);
            audit.record(username, AuditEvent.Action.LOGOUT_ALL, username, n + " session(s) closed");
            return n;
        }).orElse(0);
    }

    /** End every session of the account and invalidate its access tokens (used by logout everywhere and admin actions). */
    @Transactional
    public int revokeAllFor(UserAccount account) {
        int n = tokens.revokeAllForAccount(account.getId(), now(), "LOGOUT_ALL");
        account.setTokenVersion(account.getTokenVersion() + 1);
        accounts.save(account);
        events.publishEvent(new SessionEvents.AllSessionsEnded(account.getUsername()));
        return n;
    }

    /** Is this login session still open (at least one usable refresh token)? */
    @Transactional(readOnly = true)
    public boolean isSessionActive(String sessionId) {
        return tokens.existsActiveInFamily(sessionId, now());
    }

    @Transactional(readOnly = true)
    public long activeSessions(String username) {
        return accounts.findByUsername(username).map(a -> tokens.countActive(a.getId(), now())).orElse(0L);
    }

    /** Housekeeping: drop tokens that expired more than a day ago. */
    @Scheduled(cron = "${jobs.refresh-token-cleanup.cron:0 30 3 * * *}")
    @Transactional
    public void deleteExpired() {
        int n = tokens.deleteExpiredBefore(now().minusDays(1));
        if (n > 0) log.info("Deleted {} expired refresh token(s)", n);
    }

    public long maxAgeSeconds() {
        return props.refreshTokenDays() * 24 * 3600;
    }

    // ---------------------------------------------------------------- helpers

    private String create(UserAccount account, String family, String userAgent) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken t = new RefreshToken();
        t.setAccount(account);
        t.setTokenHash(hash(raw));
        t.setFamily(family);
        t.setCreatedAt(now());
        t.setExpiresAt(now().plusDays(props.refreshTokenDays()));
        t.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())));
        tokens.save(t);
        return raw;
    }

    static String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record Rotated(UserAccount account, String refreshToken, String sessionId) {
    }

    public record Issued(String refreshToken, String sessionId) {
    }
}
