package com.blooddonor.push;

import com.blooddonor.entity.PushSubscription;
import com.blooddonor.entity.UserAccount;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.NotFoundException;
import com.blooddonor.repository.PushSubscriptionRepository;
import com.blooddonor.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Web Push subscriptions and delivery.
 * <ul>
 *   <li>One row per browser (endpoint). If another user logs in on the same browser and subscribes,
 *       the row moves to them, so alerts never go to the previous user.</li>
 *   <li>Messages go out after commit, on a background thread; a dead subscription (404/410) is deleted,
 *       one that keeps failing for other reasons is dropped after {@value #MAX_FAILURES} attempts.</li>
 *   <li>Endpoints must be https (stops the server being used to call internal http services).</li>
 * </ul>
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    static final int MAX_FAILURES = 5;
    static final int MAX_DEVICES_PER_ACCOUNT = 10;

    public record SubscriptionRequest(String endpoint, String p256dh, String auth, String userAgent) {
    }

    private final PushSubscriptionRepository subscriptions;
    private final UserAccountRepository accounts;
    private final WebPushSender sender;
    private final TaskExecutor executor;
    private final Clock clock;
    private final boolean enabled;
    private final boolean allowHttp;

    public PushService(PushSubscriptionRepository subscriptions, UserAccountRepository accounts, WebPushSender sender,
                       @Qualifier("applicationTaskExecutor") TaskExecutor executor, Clock clock,
                       @Value("${app.push.enabled:true}") boolean enabled,
                       @Value("${app.push.allow-http-endpoints:false}") boolean allowHttp) {
        this.subscriptions = subscriptions;
        this.accounts = accounts;
        this.sender = sender;
        this.executor = executor;
        this.clock = clock;
        this.enabled = enabled;
        this.allowHttp = allowHttp;
    }

    // ---------------------------------------------------------------- subscriptions

    @Transactional
    public void subscribe(String username, SubscriptionRequest req) {
        validate(req);
        UserAccount account = accounts.findByUsername(username).orElseThrow(() -> new NotFoundException("Account", username));
        PushSubscription sub = subscriptions.findByEndpoint(req.endpoint()).orElseGet(PushSubscription::new);
        if (sub.getId() == null) {
            List<PushSubscription> existing = subscriptions.findByAccountId(account.getId());
            if (existing.size() >= MAX_DEVICES_PER_ACCOUNT) {
                // forget the oldest device rather than refusing the new one
                existing.stream().min((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                        .ifPresent(subscriptions::delete);
            }
            sub.setEndpoint(req.endpoint());
            sub.setCreatedAt(now());
        }
        sub.setAccount(account);
        sub.setP256dh(req.p256dh().trim());
        sub.setAuth(req.auth().trim());
        sub.setUserAgent(req.userAgent() == null ? null : req.userAgent().substring(0, Math.min(200, req.userAgent().length())));
        sub.setFailures(0);
        subscriptions.save(sub);
    }

    /** Only the owner can remove a subscription; removing an unknown one is a no-op. */
    @Transactional
    public void unsubscribe(String username, String endpoint) {
        subscriptions.findByEndpoint(endpoint)
                .filter(s -> s.getAccount().getUsername().equals(username))
                .ifPresent(subscriptions::delete);
    }

    @Transactional(readOnly = true)
    public int deviceCount(String username) {
        return accounts.findByUsername(username).map(a -> subscriptions.findByAccountId(a.getId()).size()).orElse(0);
    }

    /** Sends a test notification to all of this user's devices, now. Returns how many accepted it. */
    public int sendTest(String username) {
        UserAccount account = accounts.findByUsername(username).orElseThrow(() -> new NotFoundException("Account", username));
        List<PushSubscription> subs = subscriptions.findByAccountId(account.getId());
        if (subs.isEmpty()) throw new BusinessRuleException("This account has no devices with alerts turned on");
        PushMessage msg = new PushMessage(Set.of(), "🩸 Alerts are on",
                "You'll get a notification here when someone near you needs your blood group.", "/donor", "test", false);
        int ok = 0;
        for (PushSubscription s : subs) if (deliver(s, msg)) ok++;
        return ok;
    }

    // ---------------------------------------------------------------- delivery

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessage(PushMessage message) {
        if (!enabled || message.donorIds().isEmpty()) return;
        executor.execute(() -> {
            try {
                for (PushSubscription s : subscriptions.findForDonors(message.donorIds())) deliver(s, message);
            } catch (RuntimeException e) {
                log.warn("Push delivery failed: {}", e.getMessage());
            }
        });
    }

    private boolean deliver(PushSubscription s, PushMessage message) {
        try {
            WebPushSender.Result r = sender.send(s.getEndpoint(), s.getP256dh(), s.getAuth(), message.toJson(), message.urgent());
            if (r.ok()) {
                s.setLastSuccessAt(now());
                s.setFailures(0);
                subscriptions.save(s);
                return true;
            }
            if (r.gone()) {
                log.info("Push subscription #{} is gone ({}), removing it", s.getId(), r.status());
                subscriptions.delete(s);
                return false;
            }
            log.warn("Push to subscription #{} rejected with HTTP {}", s.getId(), r.status());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Push to subscription #{} failed: {}", s.getId(), e.getMessage());
        }
        s.setFailures(s.getFailures() + 1);
        if (s.getFailures() >= MAX_FAILURES) subscriptions.delete(s);
        else subscriptions.save(s);
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private void validate(SubscriptionRequest req) {
        if (req == null || req.endpoint() == null || req.p256dh() == null || req.auth() == null) {
            throw new BusinessRuleException("endpoint, p256dh and auth are required");
        }
        if (req.endpoint().length() > 1000) throw new BusinessRuleException("Push endpoint is too long");
        URI uri;
        try {
            uri = URI.create(req.endpoint());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Push endpoint is not a valid URL");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        if (uri.getHost() == null || !(https || (allowHttp && http))) {
            throw new BusinessRuleException("Push endpoint must be an https URL");
        }
        try {
            WebPushCrypto.decodePublic(WebPushCrypto.b64(req.p256dh()));
            if (WebPushCrypto.b64(req.auth()).length != 16) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new BusinessRuleException("Push keys are invalid");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
