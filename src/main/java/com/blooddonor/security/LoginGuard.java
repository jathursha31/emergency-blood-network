package com.blooddonor.security;

import com.blooddonor.entity.AuditEvent;
import com.blooddonor.entity.UserAccount;
import com.blooddonor.exception.TooManyRequestsException;
import com.blooddonor.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for password and OTP logins.
 * <ul>
 *   <li>Per username: after {@code maxFailedLogins} failures in a row (within {@code lockMinutes}) the name is
 *       locked for {@code lockMinutes}, even for the right password. Real accounts keep their counter in the
 *       database (survives restarts, visible to admins); unknown usernames are tracked in memory and locked the
 *       same way, so the answer never reveals whether an account exists.</li>
 *   <li>Per IP: {@code maxFailedLoginsPerIp} failures within {@code lockMinutes} block that address, which stops
 *       one client from trying a few passwords on many usernames.</li>
 * </ul>
 * A successful login resets the username's counter.
 */
@Service
public class LoginGuard {

    private final UserAccountRepository accounts;
    private final SecurityProperties props;
    private final AuditService audit;
    private final Clock clock;

    private final Map<String, Strikes> unknownUsers = new ConcurrentHashMap<>();
    private final Map<String, Strikes> ips = new ConcurrentHashMap<>();

    public LoginGuard(UserAccountRepository accounts, SecurityProperties props, AuditService audit, Clock clock) {
        this.accounts = accounts;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    /** Throws 429 when this IP or username is currently locked. Call before checking the password. */
    @Transactional(readOnly = true)
    public void checkAllowed(String username, String ip) {
        LocalDateTime now = now();
        Strikes ipStrikes = ip == null ? null : ips.get(ip);
        if (ipStrikes != null && ipStrikes.lockedUntil() != null && ipStrikes.lockedUntil().isAfter(now)) {
            throw locked("Too many failed logins from your network.", ipStrikes.lockedUntil());
        }
        LocalDateTime until = accounts.findByUsername(username)
                .map(UserAccount::getLockedUntil)
                .orElseGet(() -> {
                    Strikes s = unknownUsers.get(key(username));
                    return s == null ? null : s.lockedUntil();
                });
        if (until != null && until.isAfter(now)) {
            throw locked("Too many failed attempts for this account.", until);
        }
    }

    /** Count a failed login. Own transaction, because the login itself fails and rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String username, String ip) {
        LocalDateTime now = now();
        if (ip != null) {
            Strikes s = bump(ips, ip, props.maxFailedLoginsPerIp(), now);
            if (s.justLocked()) {
                audit.record(null, AuditEvent.Action.IP_BLOCKED, null,
                        ip + " blocked for " + props.lockMinutes() + " min after " + props.maxFailedLoginsPerIp() + " failed logins");
            }
        }
        accounts.findByUsername(username).ifPresentOrElse(a -> {
            boolean stale = a.getLastFailedLoginAt() == null
                    || a.getLastFailedLoginAt().isBefore(now.minusMinutes(props.lockMinutes()));
            a.setFailedLogins((stale ? 0 : a.getFailedLogins()) + 1);
            a.setLastFailedLoginAt(now);
            if (a.getFailedLogins() >= props.maxFailedLogins()) {
                a.setLockedUntil(now.plusMinutes(props.lockMinutes()));
                a.setFailedLogins(0); // after the lock, a fresh set of attempts
                audit.record(null, AuditEvent.Action.ACCOUNT_LOCKED, a.getUsername(),
                        props.maxFailedLogins() + " failed logins; locked for " + props.lockMinutes() + " min");
            }
            accounts.save(a);
        }, () -> bump(unknownUsers, key(username), props.maxFailedLogins(), now));
    }

    /** Successful login: clear the username's failures. Runs inside the login transaction. */
    public void recordSuccess(UserAccount account) {
        account.setFailedLogins(0);
        account.setLastFailedLoginAt(null);
        account.setLockedUntil(null);
    }

    /** Admin: lift a lock early. */
    public void unlock(UserAccount account) {
        recordSuccess(account);
    }

    public boolean isLocked(UserAccount account) {
        return account.getLockedUntil() != null && account.getLockedUntil().isAfter(now());
    }

    // ---------------------------------------------------------------- helpers

    private Strikes bump(Map<String, Strikes> map, String k, int max, LocalDateTime now) {
        Strikes updated = map.compute(k, (key, s) -> {
            if (s == null || s.windowStart().isBefore(now.minusMinutes(props.lockMinutes()))) s = new Strikes(0, now, null, false);
            if (s.lockedUntil() != null && s.lockedUntil().isAfter(now)) return new Strikes(s.count(), s.windowStart(), s.lockedUntil(), false);
            int count = s.count() + 1;
            return count >= max
                    ? new Strikes(0, now, now.plusMinutes(props.lockMinutes()), true)
                    : new Strikes(count, s.windowStart(), null, false);
        });
        if (map.size() > 50_000) {
            map.entrySet().removeIf(e -> e.getValue().windowStart().isBefore(now.minusMinutes(props.lockMinutes()))
                    && (e.getValue().lockedUntil() == null || e.getValue().lockedUntil().isBefore(now)));
        }
        return updated;
    }

    private TooManyRequestsException locked(String why, LocalDateTime until) {
        long seconds = Math.max(1, Duration.between(now(), until).toSeconds());
        long minutes = (seconds + 59) / 60;
        return new TooManyRequestsException(why + " Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".", seconds);
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Failures in the current window; lockedUntil set while locked; justLocked only on the failure that locked it. */
    private record Strikes(int count, LocalDateTime windowStart, LocalDateTime lockedUntil, boolean justLocked) {
    }
}
