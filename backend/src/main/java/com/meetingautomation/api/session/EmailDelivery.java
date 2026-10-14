package com.meetingautomation.api.session;

import java.time.Instant;
import java.util.UUID;

/** Privacy-safe, provider-neutral status for one selected Session roster recipient. */
public record EmailDelivery(String deliveryId, String channel, String recipientParticipantId, String status,
        int attemptCount, boolean retryable, Instant lastAttemptAt, String errorCode) {
    public static EmailDelivery pending(String participantId) {
        return new EmailDelivery("dlv_" + UUID.randomUUID(), "EMAIL", participantId,
                "PENDING", 0, false, null, null);
    }

    public EmailDelivery sending(Instant attemptedAt) {
        return new EmailDelivery(deliveryId, channel, recipientParticipantId, "SENDING",
                attemptCount + 1, false, attemptedAt, null);
    }

    public EmailDelivery retryPending() {
        return new EmailDelivery(deliveryId, channel, recipientParticipantId, "PENDING",
                attemptCount, false, lastAttemptAt, null);
    }

    public EmailDelivery sent() {
        return new EmailDelivery(deliveryId, channel, recipientParticipantId, "SENT",
                attemptCount, false, lastAttemptAt, null);
    }

    public EmailDelivery failed(boolean canRetry, String safeErrorCode) {
        return new EmailDelivery(deliveryId, channel, recipientParticipantId, "FAILED",
                attemptCount, canRetry, lastAttemptAt, safeErrorCode);
    }
}
