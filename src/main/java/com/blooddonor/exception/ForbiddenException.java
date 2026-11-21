package com.blooddonor.exception;

/** Logged in, but not allowed to touch this resource. Mapped to 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
