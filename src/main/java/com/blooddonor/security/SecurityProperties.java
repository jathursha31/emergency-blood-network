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
        /* access token lifetime; short, because it can't be revoked before it expires */
        @DefaultValue("15") long jwtExpiryMinutes,
        /* refresh token (httpOnly cookie) lifetime */
        @DefaultValue("14") long refreshTokenDays,
        /* send the refresh cookie only over https; must be true in production */
        @DefaultValue("false") boolean cookieSecure,
        /* consecutive failed logins for one username before it is locked */
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("15") int lockMinutes,
        /* failed logins from one IP address within lockMinutes before the IP is blocked */
        @DefaultValue("20") int maxFailedLoginsPerIp,
        /* created on startup if no ADMIN account exists */
        @DefaultValue("admin") String adminUsername,
        @DefaultValue("admin123") String adminPassword
) {
}
