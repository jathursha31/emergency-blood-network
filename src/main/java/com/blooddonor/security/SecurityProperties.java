package com.blooddonor.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security settings ("app.security." prefix).
 * The defaults are for local development only. In production set JWT_SECRET,
 * ADMIN_USERNAME and ADMIN_PASSWORD as environment variables.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        /* HMAC-SHA256 key, at least 32 characters */
        @DefaultValue("dev-only-secret-change-me-0123456789abcdef") String jwtSecret,
        @DefaultValue("120") long jwtExpiryMinutes,
        /* created on startup if no ADMIN account exists */
        @DefaultValue("admin") String adminUsername,
        @DefaultValue("admin123") String adminPassword
) {
}
