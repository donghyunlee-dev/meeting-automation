package com.meetingautomation.api.audio;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.meetingautomation.api.processing.FailureFinalizeService;
import com.meetingautomation.api.processing.FailureDocumentWriteException;
import com.meetingautomation.api.session.MeetingSessionStore;

/** Session-independent expiry sweep; durable object metadata remains discoverable after process restart. */
@Component
public final class PrivateAudioExpiryCleanupScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrivateAudioExpiryCleanupScheduler.class);

    private final PrivateAudioStoragePort storage;
    private final Clock clock;
    private final Optional<FailureFinalizeService> failureFinalizeService;
    private final Optional<MeetingSessionStore> sessionStore;

    @Autowired
    public PrivateAudioExpiryCleanupScheduler(PrivateAudioStoragePort storage,
            ObjectProvider<FailureFinalizeService> failureFinalizeService,
            ObjectProvider<MeetingSessionStore> sessionStore) {
        this(storage, Clock.systemUTC(), Optional.ofNullable(failureFinalizeService.getIfAvailable()),
                Optional.ofNullable(sessionStore.getIfAvailable()));
    }

    PrivateAudioExpiryCleanupScheduler(PrivateAudioStoragePort storage, Clock clock) {
        this(storage, clock, Optional.empty(), Optional.empty());
    }

    PrivateAudioExpiryCleanupScheduler(PrivateAudioStoragePort storage, Clock clock,
            Optional<FailureFinalizeService> failureFinalizeService) {
        this(storage, clock, failureFinalizeService, Optional.empty());
    }

    PrivateAudioExpiryCleanupScheduler(PrivateAudioStoragePort storage, Clock clock,
            Optional<FailureFinalizeService> failureFinalizeService, Optional<MeetingSessionStore> sessionStore) {
        this.storage = storage;
        this.clock = clock;
        this.failureFinalizeService = failureFinalizeService;
        this.sessionStore = sessionStore;
    }

    @Scheduled(fixedDelayString = "${PRIVATE_AUDIO_CLEANUP_DELAY_MS:60000}")
    public void sweep() {
        List<PrivateAudioStoragePort.StoredObject> expired;
        try {
            expired = storage.listExpired(clock.instant());
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("stage", "AUDIO_CLEANUP")
                    .addKeyValue("outcome", "listing_failed")
                    .log("Expired Audio object listing failed; a later sweep will retry.");
            return;
        }
        for (var object : expired) {
            sessionStore.ifPresentOrElse(store -> store.withSessionLock(object.sessionId(), () -> {
                processExpiredObject(object);
                return null;
            }), () -> processExpiredObject(object));
        }
    }

    private void processExpiredObject(PrivateAudioStoragePort.StoredObject object) {
        if (!isCurrentlyExpired(object.objectId())) return;
        if (sessionStore.flatMap(store -> store.find(object.sessionId()))
                .filter(session -> "PROCESSING".equals(session.status())).isPresent()) {
            LOGGER.atInfo().addKeyValue("sessionId", object.sessionId())
                    .addKeyValue("stage", "AUDIO_CLEANUP")
                    .addKeyValue("outcome", "processing_active")
                    .log("Expired Audio remains available while its Session is processing.");
            return;
        }
        try {
            if (failureFinalizeService.map(service -> service.autoFinalizeExpired(object.sessionId(), clock.instant()))
                    .orElse(false)) {
                LOGGER.atInfo().addKeyValue("sessionId", object.sessionId())
                        .addKeyValue("stage", "AUDIO_CLEANUP")
                        .addKeyValue("outcome", "failure_finalized")
                        .log("Expired failure was finalized before Audio cleanup.");
                return;
            }
        } catch (FailureDocumentWriteException failedDocument) {
            LOGGER.atWarn().addKeyValue("sessionId", object.sessionId())
                    .addKeyValue("stage", "AUDIO_CLEANUP")
                    .addKeyValue("outcome", "failure_document_pending")
                    .addKeyValue("errorCode", "DOCUMENT_FAILED")
                    .log("Expired failure document could not be written; Audio remains for a later retry.");
            return;
        } catch (RuntimeException failedFinalize) {
            LOGGER.atWarn().addKeyValue("sessionId", object.sessionId())
                    .addKeyValue("stage", "AUDIO_CLEANUP")
                    .addKeyValue("outcome", "failure_finalize_pending")
                    .addKeyValue("errorCode", "FINALIZE_FAILED")
                    .log("Expired failure could not be finalized; Audio remains for a later retry.");
            return;
        }
        if (!isCurrentlyExpired(object.objectId())) return;
        try {
            storage.delete(object.objectId());
            LOGGER.atInfo().addKeyValue("sessionId", object.sessionId())
                    .addKeyValue("stage", "AUDIO_CLEANUP")
                    .addKeyValue("outcome", "deleted")
                    .log("Expired private Audio object deleted.");
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("sessionId", object.sessionId())
                    .addKeyValue("stage", "AUDIO_CLEANUP")
                    .addKeyValue("outcome", "delete_pending")
                    .addKeyValue("errorCode", "AUDIO_STORAGE_FAILED")
                    .log("Expired private Audio object cleanup will be retried.");
        }
    }

    private boolean isCurrentlyExpired(String objectId) {
        try {
            return storage.find(objectId)
                    .filter(current -> current.expiresAt() != null && !current.expiresAt().isAfter(clock.instant()))
                    .isPresent();
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("stage", "AUDIO_CLEANUP")
                    .addKeyValue("outcome", "metadata_recheck_failed")
                    .addKeyValue("errorCode", "AUDIO_STORAGE_FAILED")
                    .log("Expired Audio metadata could not be rechecked; cleanup will be retried later.");
            return false;
        }
    }
}
