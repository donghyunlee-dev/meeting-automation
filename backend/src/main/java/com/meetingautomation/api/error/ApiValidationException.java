package com.meetingautomation.api.error;

/** Internal signal for a safe request field validation failure. */
public final class ApiValidationException extends RuntimeException {
    private final String field;

    public ApiValidationException(String field) {
        super("Request validation failed.");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
