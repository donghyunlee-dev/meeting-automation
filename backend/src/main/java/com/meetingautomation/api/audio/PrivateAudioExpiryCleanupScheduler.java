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
import com.meetingautomation.api.processing.FailureDocumentWriteException;
import com.meetingautomation.api.processing.FailureFinalizeService;

/** Session-independent expiry sweep; durable object metadata remains discoverable after process restart. */
@Component
public final class PrivateAudioExpiryCleanupScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrivateAudioExpiryCleanupScheduler.class);

    private final PrivateAudioStoragePort storage;
    private final Clock clock;
    private final Optional<FailureFinalizeService> failureFinalizeService;

    @Autowired
    public PrivateAudioExpiryCleanupScheduler(PrivateAudioStoragePort storage,
            ObjectProvider<FailureFinalizeService> failureFinalizeService) {
        this(storage, Clock.systemUTC(), Optional.ofNullable(failureFinalizeService.getIfAvailable()));
    }

    PrivateAudioExpiryCleanupScheduler(PrivateAudioStoragePort storage, Clock clock) {
        this(storage, clock, Optional.empty());
    }

    PrivateAudioExpiryCleanupScheduler(PrivateAudioStoragePort storage, Clock clock,
            Optional<FailureFinalizeService> failureFinalizeService) {
        this.storage = storage;
        this.clock = clock;
        this.failureFinalizeService = failureFinalizeService;
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
            if (!isCurrentlyExpired(object.objectId())) continue;
            try {
                if (failureFinalizeService.map(service -> service.autoFinalizeExpired(object.sessionId(), clock.instant()))
                        .orElse(false)) {
                    LOGGER.atInfo().addKeyValue("sessionId", object.sessionId())
                            .addKeyValue("stage", "AUDIO_CLEANUP")
                            .addKeyValue("outcome", "failure_finalized")
                            .log("Expired failure was finalized before Audio cleanup.");
                    continue;
                }
            } catch (FailureDocumentWriteException failedDocument) {
                LOGGER.atWarn().addKeyValue("sessionId", object.sessionId())
                        .addKeyValue("stage", "AUDIO_CLEANUP")
                        .addKeyValue("outcome", "failure_document_pending")
                        .addKeyValue("errorCode", "DOCUMENT_FAILED")
                        .log("Expired failure document could not be written; Audio remains for a later retry.");
                continue;
            } catch (RuntimeException failedFinalize) {
                LOGGER.atWarn().addKeyValue("sessionId", object.sessionId())
                        .addKeyValue("stage", "AUDIO_CLEANUP")
                        .addKeyValue("outcome", "failure_finalize_pending")
                        .addKeyValue("errorCode", "FINALIZE_FAILED")
                        .log("Expired failure could not be finalized; Audio remains for a later retry.");
                continue;
            }
            if (!isCurrentlyExpired(object.objectId())) continue;
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
