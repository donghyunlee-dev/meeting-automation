package com.meetingautomation.api.session;

import java.time.Instant;
import java.util.UUID;

/** Provider-neutral publish notification outcome. It deliberately has no recipient. */
public record NotificationDelivery(String deliveryId, String status, int attemptCount,
        boolean retryable, Instant lastAttemptAt, String errorCode) {
    public static NotificationDelivery pending() {
        return new NotificationDelivery("dlv_" + UUID.randomUUID(), "PENDING", 0, false, null, null);
    }

    public NotificationDelivery sending(Instant attemptedAt) {
        return new NotificationDelivery(deliveryId, "SENDING", attemptCount + 1, false, attemptedAt, null);
    }

    public NotificationDelivery retryPending() {
        return new NotificationDelivery(deliveryId, "PENDING", attemptCount, false, lastAttemptAt, null);
    }

    public NotificationDelivery sent() {
        return new NotificationDelivery(deliveryId, "SENT", attemptCount, false, lastAttemptAt, null);
    }

    public NotificationDelivery failed(boolean canRetry, String safeErrorCode) {
        return new NotificationDelivery(deliveryId, "FAILED", attemptCount, canRetry, lastAttemptAt, safeErrorCode);
    }
}
