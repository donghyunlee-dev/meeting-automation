package com.meetingautomation.notification;

/** Safe, provider-neutral outcome. Provider response bodies and credentials are never retained. */
public record DeliveryResult(Status status, boolean retryable, String errorCode) {
    public enum Status { SENT, FAILED }

    public static DeliveryResult sent() { return new DeliveryResult(Status.SENT, false, null); }
    public static DeliveryResult failed(boolean retryable) {
        return new DeliveryResult(Status.FAILED, retryable, "NOTIFICATION_FAILED");
    }
}
