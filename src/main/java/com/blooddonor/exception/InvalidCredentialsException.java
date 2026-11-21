package com.blooddonor.exception;

/** Wrong username or password. Mapped to 401. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}
