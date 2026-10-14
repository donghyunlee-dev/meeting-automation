package com.meetingautomation.api.session;

public final class DeliveryRetryException extends RuntimeException {
    public enum Reason { NOT_FOUND, NOT_RETRYABLE, IN_PROGRESS }

    private final Reason reason;

    public DeliveryRetryException(Reason reason) {
        super("Delivery retry was rejected.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
