package com.blooddonor.notification;

import com.blooddonor.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a notification into an SMS.
 * <ul>
 *   <li>Normalises numbers to E.164 (9840000001 -> +919840000001, 04420001111 -> +914420001111).</li>
 *   <li>Waits until the current database transaction commits, so nobody is texted about a change
 *       that was rolled back.</li>
 *   <li>Sends on a background thread, so a slow or failing SMS provider never slows down or breaks the API call.</li>
 *   <li>Honours app.sms.allowed-numbers while testing.</li>
 * </ul>
 */
@Service
public class SmsNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationService.class);

    private final SmsSender sender;
    private final TaskExecutor executor;
    private final String countryCode;
    private final Set<String> allowed;

    public SmsNotificationService(SmsSender sender, SmsProperties props,
                                  @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.sender = sender;
        this.executor = executor;
        this.countryCode = props.countryCode();
        this.allowed = props.allowedNumbers().stream()
                .map(n -> normalize(n, props.countryCode()))
                .collect(Collectors.toUnmodifiableSet());
        log.info("SMS provider: {}{}", sender.name(),
                allowed.isEmpty() ? "" : " (only to " + allowed.size() + " allowed number(s))");
    }

    @Override
    public void send(String phone, String message) {
        String to = normalize(phone, countryCode);
        Runnable task = () -> deliver(to, message);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    private void deliver(String to, String message) {
        if (!allowed.isEmpty() && !allowed.contains(to) && !"log".equals(sender.name())) {
            log.info("[SMS skipped, {} not in app.sms.allowed-numbers] {}", to, message);
            return;
        }
        try {
            sender.send(to, message);
            if (!"log".equals(sender.name())) log.info("SMS sent via {} to {}", sender.name(), to);
        } catch (RuntimeException e) {
            log.warn("SMS to {} via {} failed: {}", to, sender.name(), e.getMessage());
        }
    }

    /** Local Indian numbers to E.164. Numbers that already start with + are kept as they are. */
    static String normalize(String phone, String countryCode) {
        if (phone == null) return "";
        String trimmed = phone.trim();
        if (trimmed.startsWith("+")) return "+" + trimmed.replaceAll("\\D", "");
        String digits = trimmed.replaceAll("\\D", "");
        String cc = countryCode.replaceAll("\\D", "");
        if (digits.length() == 10) return "+" + cc + digits;                                     // 9840000001
        if (digits.length() == 11 && digits.startsWith("0")) return "+" + cc + digits.substring(1); // 04420001111
        if (digits.length() == 12 && digits.startsWith(cc)) return "+" + digits;                   // 919840000001
        return "+" + digits;
    }
}
