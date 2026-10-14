package com.meetingautomation.api.session;

import com.meetingautomation.api.error.ApiValidationException;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
final class DeliveryRetryService {
    private static final Pattern SESSION_ID = Pattern.compile("ms_[A-Za-z0-9_-]{1,128}");
    private static final Pattern DELIVERY_ID = Pattern.compile("dlv_[A-Za-z0-9-]{36}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final MeetingSessionStore sessions;
    private final DeliveryRetryJobRunner runner;
    private final Executor executor;

    DeliveryRetryService(MeetingSessionStore sessions, DeliveryRetryJobRunner runner,
            @Qualifier("processingJobExecutor") Executor executor) {
        this.sessions = sessions;
        this.runner = runner;
        this.executor = executor;
    }

    DeliveryRetryResponse retry(String sessionId, String deliveryId, String keyHeader) {
        String key = normalizeKey(keyHeader);
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches())
            throw new ApiValidationException("sessionId");
        if (deliveryId == null || !DELIVERY_ID.matcher(deliveryId).matches())
            throw new ApiValidationException("deliveryId");
        return sessions.withSessionLock(sessionId, () -> {
            MeetingSessionStore.DeliveryRetryAcceptance accepted =
                    sessions.acceptDeliveryRetry(sessionId, deliveryId, key);
            if (!accepted.newlyAccepted()) return accepted.response();
            try {
                executor.execute(() -> runner.run(sessionId, deliveryId));
                return accepted.response();
            } catch (RuntimeException rejected) {
                sessions.rollbackDeliveryRetry(sessionId, deliveryId, key, accepted);
                throw new IllegalStateException("Delivery retry could not be queued.");
            }
        });
    }

    private static String normalizeKey(String key) {
        if (key == null || !KEY.matcher(key.trim()).matches()) throw new ApiValidationException("Idempotency-Key");
        return key.trim();
    }
}
