package com.meetingautomation.api.session;

import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.minutes.MinutesGenerationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs a regeneration outside the request thread and fences all terminal writes by operation id. */
@Service
final class MinutesRegenerationJobRunner {
    private static final Logger LOG = LoggerFactory.getLogger(MinutesRegenerationJobRunner.class);
    private final MeetingSessionStore sessionStore;
    private final MinutesGenerationUseCase generation;

    MinutesRegenerationJobRunner(MeetingSessionStore sessionStore, MinutesGenerationUseCase generation) {
        this.sessionStore = sessionStore;
        this.generation = generation;
    }

    void run(MinutesRegenerationJob job) {
        if (job == null || job.sessionId() == null || job.operationId() == null
                || !sessionStore.isActiveMinutesRegeneration(job.sessionId(), job.operationId())) return;
        try {
            MeetingSession snapshot = sessionStore.find(job.sessionId()).orElse(null);
            if (snapshot == null || !sessionStore.isActiveMinutesRegeneration(job.sessionId(), job.operationId())) return;
            StructuredMinutes result = generation.generate(snapshot, job.template());
            boolean committed = sessionStore.withSessionLock(job.sessionId(), () -> {
                if (sessionStore.isActiveMinutesRegeneration(job.sessionId(), job.operationId())) {
                    sessionStore.completeMinutesRegeneration(job.sessionId(), job.operationId(),
                            job.template().id(), job.template().version(), result);
                    return true;
                }
                return false;
            });
            LOG.info("Minutes regeneration sessionId={} outcome={}", job.sessionId(),
                    committed ? "SUCCEEDED" : "STALE");
        } catch (RuntimeException safeFailure) {
            try {
                sessionStore.withSessionLock(job.sessionId(), () -> {
                    if (sessionStore.isActiveMinutesRegeneration(job.sessionId(), job.operationId())) {
                        sessionStore.failMinutesRegeneration(job.sessionId(), job.operationId());
                    }
                    return null;
                });
                LOG.warn("Minutes regeneration sessionId={} outcome=FAILED errorCode=MINUTES_REGENERATION_FAILED",
                        job.sessionId());
            } catch (RuntimeException fencedFailure) {
                LOG.info("Minutes regeneration sessionId={} outcome=STALE", job.sessionId());
            }
        }
    }
}

