package com.blooddonor.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * SMS settings (prefix "app.sms").
 *
 * <pre>
 * app.sms.provider=log | twilio
 * app.sms.country-code=+91
 * app.sms.allowed-numbers=+919876543210,+919812345678   (empty = send to everyone)
 * app.sms.twilio.account-sid=AC...
 * app.sms.twilio.auth-token=...
 * app.sms.twilio.from-number=+1...
 * </pre>
 */
@ConfigurationProperties(prefix = "app.sms")
public record SmsProperties(
        /* "log" only writes messages to the console; "twilio" really sends them */
        @DefaultValue("log") String provider,
        /* prefix added to 10-digit local numbers */
        @DefaultValue("+91") String countryCode,
        /*
         * Safety net while testing: when non-empty, real SMS go only to these numbers (E.164),
         * everything else is just logged. The sample data uses made-up numbers that may belong to real people.
         */
        @DefaultValue List<String> allowedNumbers,
        @DefaultValue Twilio twilio
) {

    public record Twilio(
            String accountSid,
            String authToken,
            String fromNumber,
            @DefaultValue("https://api.twilio.com") String apiBaseUrl
    ) {
    }
}
