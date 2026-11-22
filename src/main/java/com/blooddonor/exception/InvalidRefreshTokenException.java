package com.blooddonor.exception;

/** Missing, unknown, expired or revoked refresh token. Mapped to 401; the client must log in again. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
