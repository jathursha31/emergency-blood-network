package com.blooddonor.security;

import com.blooddonor.repository.UserAccountRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes logout take effect on access tokens immediately, not only when they expire.
 * Every request's JWT is checked for:
 * <ul>
 *   <li>"sid": its login session must still be open (logout on that device closes it);</li>
 *   <li>"tv": it must carry the account's current token version (log out everywhere bumps it).</li>
 * </ul>
 * Answers are cached for a few seconds so normal requests don't hit the database, and revocations
 * made by this server update the cache at once (after commit), so they apply instantly here.
 * With several servers, other instances would see a revocation within the cache time (or use Redis pub/sub).
 */
@Component
public class SessionGuard implements OAuth2TokenValidator<Jwt> {

    static final long CACHE_MILLIS = 30_000;

    private final RefreshTokenService refreshTokens;
    private final UserAccountRepository accounts;
    private final Clock clock;

    private final Map<String, Cached<Boolean>> sessionActive = new ConcurrentHashMap<>();
    private final Map<String, Cached<Integer>> tokenVersion = new ConcurrentHashMap<>();

    public SessionGuard(RefreshTokenService refreshTokens, UserAccountRepository accounts, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sid = jwt.getClaimAsString(CurrentUser.SESSION_CLAIM);
        if (sid != null && !cached(sessionActive, sid, () -> refreshTokens.isSessionActive(sid))) {
            return fail("This session has been logged out");
        }
        Long tv = CurrentUser.longClaim(jwt, CurrentUser.TOKEN_VERSION_CLAIM);
        String username = jwt.getSubject();
        Integer current = cached(tokenVersion, username, () -> accounts.findByUsername(username)
                .filter(a -> a.isEnabled())
                .map(a -> a.getTokenVersion())
                .orElse(-1));
        if (current < 0) return fail("Account not found or disabled");
        if ((tv == null ? 0 : tv) != current) return fail("You logged out on all devices");
        return OAuth2TokenValidatorResult.success();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionEnded(SessionEvents.SessionEnded e) {
        sessionActive.put(e.sessionId(), new Cached<>(false, Long.MAX_VALUE)); // never comes back
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAllSessionsEnded(SessionEvents.AllSessionsEnded e) {
        tokenVersion.remove(e.username());
        sessionActive.clear(); // cheap to rebuild; avoids tracking which sessions belonged to the account
    }

    private <T> T cached(Map<String, Cached<T>> map, String key, java.util.function.Supplier<T> load) {
        long now = clock.millis();
        Cached<T> c = map.get(key);
        if (c != null && c.validUntil() > now) return c.value();
        T value = load.get();
        map.put(key, new Cached<>(value, now + CACHE_MILLIS));
        if (map.size() > 10_000) map.entrySet().removeIf(en -> en.getValue().validUntil() <= now);
        return value;
    }

    private static OAuth2TokenValidatorResult fail(String why) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, why, null));
    }

    private record Cached<T>(T value, long validUntil) {
    }
}
