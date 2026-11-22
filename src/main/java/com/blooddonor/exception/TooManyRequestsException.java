package com.blooddonor.exception;

/** Rate limit hit (e.g. asking for OTP codes too often). Mapped to 429. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
