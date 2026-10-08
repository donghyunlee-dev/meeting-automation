package com.meetingautomation.api.idempotency;

public final class IdempotencyKeyConflictException extends RuntimeException {
    public IdempotencyKeyConflictException() {
        super("Idempotency key is already associated with a different request.");
    }
}
