package com.blooddonor.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default sender: prints the message instead of sending it. Used when app.sms.provider=log (or unset). */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String e164Phone, String text) {
        log.info("[SMS to {}] {}", e164Phone, text);
    }

    @Override
    public String name() {
        return "log";
    }
}
