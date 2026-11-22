package com.blooddonor.security;

import com.blooddonor.entity.AuditEvent;
import com.blooddonor.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Append-only audit log of security events (logins, logouts, account changes).
 * Each record is written in its own transaction, so it is kept even when the surrounding
 * work fails (a failed login throws, but "LOGIN_FAILED" must still be stored).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, AuditEvent.Action action, String target, String details) {
        AuditEvent e = new AuditEvent();
        e.setAt(LocalDateTime.now(clock));
        e.setActor(cut(actor, 60));
        e.setAction(action);
        e.setTarget(cut(target, 60));
        e.setDetails(cut(details, 300));
        e.setIp(RequestIp.current());
        events.save(e);
        log.info("AUDIT {} actor={} target={} {}", action, actor, target, details == null ? "" : details);
    }

    /** Same, with the logged-in user (from the security context) as the actor. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent.Action action, String target, String details) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        record(auth != null && auth.isAuthenticated() ? auth.getName() : null, action, target, details);
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> search(String user, AuditEvent.Action action, int page, int size) {
        return events.search(blankToNull(user), action, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 200)));
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
