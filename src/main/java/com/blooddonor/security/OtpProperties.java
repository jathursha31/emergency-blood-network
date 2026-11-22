package com.blooddonor.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** One-time codes by SMS (prefix "app.otp"). */
@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        /* donors must verify their phone with a code to sign up */
        @DefaultValue("true") boolean requiredForRegister,
        @DefaultValue("5") int validMinutes,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30") int resendAfterSeconds,
        @DefaultValue("5") int maxPerHour,
        /* return the code in the API response, ONLY while SMS are just logged (local development) */
        @DefaultValue("true") boolean showCodeInDev
) {
}
