package com.blooddonor.exception;

/** A valid request that breaks a business rule (e.g. donor not eligible yet). Mapped to 409. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
