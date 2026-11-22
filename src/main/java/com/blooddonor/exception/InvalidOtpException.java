package com.blooddonor.exception;

/** Wrong, expired or already-used one-time code. Mapped to 401. */
public class InvalidOtpException extends RuntimeException {

    public InvalidOtpException(String message) {
        super(message);
    }
}
