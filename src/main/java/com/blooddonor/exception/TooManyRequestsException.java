package com.blooddonor.exception;

/** Rate limit hit (OTP requests, too many failed logins). Mapped to 429 with a Retry-After header. */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message) {
        this(message, 0);
    }

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
