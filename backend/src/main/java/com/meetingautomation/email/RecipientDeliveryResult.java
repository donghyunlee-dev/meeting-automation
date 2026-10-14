package com.meetingautomation.email;

public record RecipientDeliveryResult(String recipientParticipantId, Status status, boolean retryable,
        String errorCode) {
    public enum Status { SENT, FAILED }

    public static RecipientDeliveryResult sent(String participantId) {
        return new RecipientDeliveryResult(participantId, Status.SENT, false, null);
    }

    public static RecipientDeliveryResult failed(String participantId, boolean retryable) {
        return new RecipientDeliveryResult(participantId, Status.FAILED, retryable, "EMAIL_FAILED");
    }
}
