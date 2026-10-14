package com.meetingautomation.api.processing;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Serializes deliveries per session and invokes the pipeline once for the accepted API-009 job. */
@Service
public class ProcessingJobRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingJobRunner.class);
    private final MeetingSessionStore sessionStore;
    private final ProcessingPipeline pipeline;
    private final ConcurrentMap<String, Boolean> activeJobs = new ConcurrentHashMap<>();

    public ProcessingJobRunner(MeetingSessionStore sessionStore, ProcessingPipeline pipeline) {
        this.sessionStore = sessionStore;
        this.pipeline = pipeline;
    }

    public void run(ProcessingJob job) {
        if (job == null || job.sessionId() == null || job.sessionId().isBlank()) return;
        String sessionId = job.sessionId();
        String traceId = "tr_" + UUID.randomUUID();
        boolean claimed;
        try {
            claimed = sessionStore.withSessionLock(sessionId, () -> {
                if (activeJobs.putIfAbsent(sessionId, Boolean.TRUE) != null) {
                    logSkipped(sessionId, traceId, "ACTIVE_JOB");
                    return false;
                }
                MeetingSession session = sessionStore.find(sessionId).orElse(null);
                if (session == null) {
                    activeJobs.remove(sessionId);
                    logSkipped(sessionId, traceId, "SESSION_NOT_FOUND");
                    return false;
                }
                boolean initial = "AUDIO_ASSEMBLY".equals(session.processingStage())
                        && session.progressPercent() == 0;
                boolean retry = session.processingFailure() == null
                        && Set.of("TRANSCRIPTION", "DIARIZATION", "MINUTES_GENERATION")
                                .contains(session.processingStage());
                if (!"PROCESSING".equals(session.status()) || !(initial || retry)) {
                    activeJobs.remove(sessionId);
                    logSkipped(sessionId, traceId, "DUPLICATE_DELIVERY");
                    return false;
                }
                return true;
            });
        } catch (RuntimeException safeFailure) {
            LOGGER.atError()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("traceId", traceId)
                    .addKeyValue("errorCode", "PROCESSING_RUNNER_FAILED")
                    .log("Processing job runner failed.");
            return;
        }
        if (!claimed) return;

        try {
            pipeline.process(job, traceId);
        } catch (RuntimeException unexpected) {
            try {
                sessionStore.withSessionLock(sessionId, () -> {
                    MeetingSession latest = sessionStore.find(sessionId).orElse(null);
                    String stage = latest == null || latest.processingStage() == null
                            ? "AUDIO_ASSEMBLY" : latest.processingStage();
                    if (latest != null && "PROCESSING".equals(latest.status())) {
                        sessionStore.failProcessing(sessionId, stage, lastCompletedProgress(stage),
                                "PROCESSING_FAILED", false);
                    }
                    LOGGER.atError()
                            .addKeyValue("sessionId", sessionId)
                            .addKeyValue("traceId", traceId)
                            .addKeyValue("stage", stage)
                            .addKeyValue("errorCode", "PROCESSING_FAILED")
                            .log("Processing job failed unexpectedly.");
                    return null;
                });
            } catch (RuntimeException safeFailure) {
                LOGGER.atError()
                        .addKeyValue("sessionId", sessionId)
                        .addKeyValue("traceId", traceId)
                        .addKeyValue("errorCode", "PROCESSING_RUNNER_FAILED")
                        .log("Processing job runner failed.");
            }
        } finally {
            activeJobs.remove(sessionId);
        }
    }

    private static int lastCompletedProgress(String stage) {
        return switch (stage) {
            case "TRANSCRIPTION" -> 33;
            case "DIARIZATION" -> 66;
            default -> 0;
        };
    }

    private static void logSkipped(String sessionId, String traceId, String reason) {
        LOGGER.atInfo()
                .addKeyValue("sessionId", sessionId)
                .addKeyValue("traceId", traceId)
                .addKeyValue("stage", "AUDIO_ASSEMBLY")
                .addKeyValue("outcome", "skipped")
                .addKeyValue("reason", reason)
                .log("Processing job delivery skipped.");
    }
}
