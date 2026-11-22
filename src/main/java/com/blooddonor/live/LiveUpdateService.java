package com.blooddonor.live;

import com.blooddonor.security.CurrentUser;
import com.blooddonor.security.SessionEvents;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes {@link LiveEvent}s to logged-in browsers over Server-Sent Events.
 * Events are sent only after the database transaction commits, so screens never show a change that was rolled back.
 * (One-way server -> browser updates are all we need, so SSE is simpler than WebSockets and works with the normal JWT header.)
 */
@Service
public class LiveUpdateService {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateService.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // browser reconnects after this

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(CurrentUser user) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Subscriber sub = new Subscriber(emitter, user);
        subscribers.add(sub);
        emitter.onCompletion(() -> subscribers.remove(sub));
        emitter.onTimeout(() -> { subscribers.remove(sub); emitter.complete(); });
        emitter.onError(e -> subscribers.remove(sub));
        send(sub, "connected", java.util.Map.of("role", user.role().name()));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(LiveEvent event) {
        for (Subscriber sub : subscribers) {
            if (sub.wants(event)) send(sub, event.type(), event.data());
        }
    }

    /** Keeps proxies from closing idle connections and drops dead ones. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        for (Subscriber sub : subscribers) {
            try {
                sub.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(sub);
            }
        }
    }

    /**
     * Close every stream as soon as shutdown starts. ContextClosedEvent fires before the web server's
     * graceful shutdown, which would otherwise wait up to 30 s for these never-ending requests.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        for (Subscriber sub : subscribers) {
            try { sub.emitter().complete(); } catch (RuntimeException ignored) { /* already closed */ }
        }
        subscribers.clear();
    }

    /** A logged-out session must stop receiving updates at once, not when its stream times out. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionEnded(SessionEvents.SessionEnded e) {
        disconnectWhere(s -> e.sessionId().equals(s.user().sessionId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAllSessionsEnded(SessionEvents.AllSessionsEnded e) {
        disconnectWhere(s -> e.username().equals(s.user().username()));
    }

    private void disconnectWhere(java.util.function.Predicate<Subscriber> match) {
        for (Subscriber sub : subscribers) {
            if (!match.test(sub)) continue;
            subscribers.remove(sub);
            try {
                sub.emitter().send(SseEmitter.event().name("session.ended").data(java.util.Map.of("reason", "logged out")));
                sub.emitter().complete();
            } catch (IOException | IllegalStateException ignored) { /* already gone */ }
        }
    }

    public int connectionCount() {
        return subscribers.size();
    }

    private void send(Subscriber sub, String type, Object data) {
        try {
            sub.emitter().send(SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping live connection of {}: {}", sub.user().username(), e.getMessage());
            subscribers.remove(sub);
        }
    }

    private record Subscriber(SseEmitter emitter, CurrentUser user) {
        boolean wants(LiveEvent e) {
            return switch (user.role()) {
                case ADMIN -> true;
                case HOSPITAL -> e.hospitalIds().contains(user.hospitalId());
                case DONOR -> e.donorIds().contains(user.donorId());
            };
        }
    }
}
