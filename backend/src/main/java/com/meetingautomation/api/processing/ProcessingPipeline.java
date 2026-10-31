package com.meetingautomation.api.processing;

import com.meetingautomation.api.audio.AudioAssemblyStorage;
import com.meetingautomation.api.error.FailureContext;
import com.meetingautomation.api.error.FailureContextReporter;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.ProcessingFailure;
import com.meetingautomation.api.session.UploadPolicy;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.TranscriptResult;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.minutes.MinutesGenerationException;
import com.meetingautomation.minutes.MinutesGenerationUseCase;
import com.meetingautomation.transcription.DiarizationResultAdapter;
import com.meetingautomation.transcription.ProviderTranscriptionResponse;
import com.meetingautomation.transcription.TranscriptionCommand;
import com.meetingautomation.transcription.TranscriptionException;
import com.meetingautomation.transcription.TranscriptionProvider;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Runs the ordered audio assembly, transcription, and diarization stages for one processing job. */
@Service
public class ProcessingPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingPipeline.class);
    private static final String AUDIO_ASSEMBLY = "AUDIO_ASSEMBLY";
    private static final String TRANSCRIPTION = "TRANSCRIPTION";
    private static final String DIARIZATION = "DIARIZATION";
    private static final String MINUTES_GENERATION = "MINUTES_GENERATION";

    private final MeetingSessionStore sessionStore;
    private final AudioAssemblyUseCase audioAssembly;
    private final TranscriptionProvider transcriptionProvider;
    private final DiarizationResultAdapter diarizationAdapter;
    private final MinutesGenerationUseCase minutesGeneration;
    private final FailureContextReporter failureContextReporter;
    private final Clock clock;

    ProcessingPipeline(MeetingSessionStore sessionStore, AudioAssemblyUseCase audioAssembly,
            TranscriptionProvider transcriptionProvider, DiarizationResultAdapter diarizationAdapter,
            MinutesGenerationUseCase minutesGeneration, FailureContextReporter failureContextReporter,
            Clock processingClock) {
        this.sessionStore = sessionStore;
        this.audioAssembly = audioAssembly;
        this.transcriptionProvider = transcriptionProvider;
        this.diarizationAdapter = diarizationAdapter;
        this.minutesGeneration = minutesGeneration;
        this.failureContextReporter = failureContextReporter;
        this.clock = processingClock;
    }

    @Autowired
    public ProcessingPipeline(MeetingSessionStore sessionStore, AudioAssemblyUseCase audioAssembly,
            TranscriptionProvider transcriptionProvider, DiarizationResultAdapter diarizationAdapter,
            MinutesGenerationUseCase minutesGeneration, FailureContextReporter failureContextReporter) {
        this(sessionStore, audioAssembly, transcriptionProvider, diarizationAdapter, minutesGeneration,
                failureContextReporter, Clock.systemUTC());
    }

    public void process(ProcessingJob job, String traceId) {
        if (job == null || job.sessionId() == null || job.sessionId().isBlank()) return;
        String sessionId = job.sessionId();
        String effectiveTraceId = traceId == null || traceId.isBlank() ? "tr_" + UUID.randomUUID() : traceId;
        MeetingSession session = sessionStore.find(sessionId).orElse(null);
        if (session == null || !"PROCESSING".equals(session.status())) return;

        UploadPolicy policy = sessionStore.findUploadPolicy(sessionId).orElse(null);
        long stageStartedAt = System.nanoTime();
        boolean minutesOnlyRetry = MINUTES_GENERATION.equals(session.processingStage());
        if (!minutesOnlyRetry) {
            boolean assembling = AUDIO_ASSEMBLY.equals(session.processingStage());
            AudioAssemblyStorage.Assembly assembly;
            if (assembling) {
                logStageStarted(sessionId, effectiveTraceId, AUDIO_ASSEMBLY);
                try {
                    if (policy == null || job.expectedChunks() < 1 || job.durationMs() < 1
                            || job.mimeType() == null || job.mimeType().isBlank()) {
                        throw new AudioAssemblyException("AUDIO_ASSEMBLY_INVALID_INPUT");
                    }
                    assembly = audioAssembly.assemble(job, policy, effectiveTraceId);
                    if (assembly == null || assembly.reference() == null || assembly.mimeType() == null
                            || assembly.mimeType().isBlank() || assembly.bytes() < 1) {
                        throw new AudioAssemblyException("AUDIO_ASSEMBLY_OUTPUT_INVALID");
                    }
                } catch (AudioAssemblyException failure) {
                    fail(sessionId, effectiveTraceId, AUDIO_ASSEMBLY, 0,
                            safeCode(failure.code(), "AUDIO_ASSEMBLY_FAILED"),
                            isRetryableAssemblyFailure(failure.code()), stageStartedAt,
                            audioAssembly.hasRetryInputs(job, policy));
                    return;
                } catch (RuntimeException failure) {
                    fail(sessionId, effectiveTraceId, AUDIO_ASSEMBLY, 0, "AUDIO_ASSEMBLY_FAILED", true,
                            stageStartedAt, audioAssembly.hasRetryInputs(job, policy));
                    return;
                }
                sessionStore.recordProcessingProgress(sessionId, AUDIO_ASSEMBLY, 33);
                logStageSucceeded(sessionId, effectiveTraceId, AUDIO_ASSEMBLY, stageStartedAt);
            } else {
                assembly = audioAssembly.findAssembly(sessionId).orElse(null);
                if (assembly == null) {
                    fail(sessionId, effectiveTraceId, session.processingStage(), session.progressPercent(),
                            "AUDIO_NOT_AVAILABLE", false, stageStartedAt, false);
                    return;
                }
            }

            ProviderTranscriptionResponse providerResponse;
            boolean resumingDiarization = DIARIZATION.equals(session.processingStage());
            if (resumingDiarization) {
                providerResponse = sessionStore.findTranscriptionResult(sessionId).orElse(null);
                if (providerResponse == null) {
                    fail(sessionId, effectiveTraceId, DIARIZATION, 66,
                            "TRANSCRIPTION_RESULT_NOT_AVAILABLE", false, stageStartedAt);
                    return;
                }
            } else {
                sessionStore.recordProcessingProgress(sessionId, TRANSCRIPTION, 34);
                stageStartedAt = System.nanoTime();
                logStageStarted(sessionId, effectiveTraceId, TRANSCRIPTION);
                LOGGER.info("Transcription input prepared sessionId={} traceId={} audioBytes={} meetingDurationMs={} mimeType={}",
                        sessionId, effectiveTraceId, assembly.bytes(), job.durationMs(), assembly.mimeType());
                try {
                    try (var audio = audioAssembly.openAssembly(assembly)) {
                        TranscriptionCommand command = new TranscriptionCommand(
                                audio, assembly.bytes(), assembly.mimeType(), "ko", job.durationMs(), effectiveTraceId);
                        providerResponse = transcriptionProvider.transcribeWithDiarization(command);
                    } catch (java.io.IOException invalidStoredAudio) {
                        fail(sessionId, effectiveTraceId, TRANSCRIPTION, 33,
                                "TRANSCRIPTION_INVALID_AUDIO", false, stageStartedAt);
                        return;
                    }
                    if (providerResponse == null) {
                        fail(sessionId, effectiveTraceId, TRANSCRIPTION, 33,
                                "TRANSCRIPTION_INVALID_RESPONSE", false, stageStartedAt);
                        return;
                    }
                } catch (TranscriptionException failure) {
                    String code = failure.code() == null ? "TRANSCRIPTION_FAILED" : "TRANSCRIPTION_" + failure.code();
                    fail(sessionId, effectiveTraceId, TRANSCRIPTION, 33,
                            safeCode(code, "TRANSCRIPTION_FAILED"), failure.retryable(), stageStartedAt);
                    return;
                } catch (RuntimeException failure) {
                    fail(sessionId, effectiveTraceId, TRANSCRIPTION, 33, "TRANSCRIPTION_FAILED", false, stageStartedAt);
                    return;
                }
            }

            ProviderTranscriptionResponse providerResponseBeforeAlignment = providerResponse;
            try {
                providerResponse = providerResponse.reconcileToMeetingDuration(job.durationMs());
                if (providerResponse != providerResponseBeforeAlignment) {
                    long maxProviderEndMs = providerResponseBeforeAlignment.segments().stream()
                            .mapToLong(ProviderTranscriptionResponse.Segment::endMs).max().orElse(-1);
                    long adjustedSegments = providerResponseBeforeAlignment.segments().stream()
                            .filter(segment -> segment.endMs() > job.durationMs()).count();
                    LOGGER.info("Transcription response timestamps aligned sessionId={} traceId={} meetingDurationMs={} segmentCount={} adjustedSegmentCount={} maxProviderEndMs={} maxOverrunMs={}",
                            sessionId, effectiveTraceId, job.durationMs(), providerResponse.segments().size(),
                            adjustedSegments, maxProviderEndMs, maxProviderEndMs - job.durationMs());
                }
                if (!resumingDiarization || providerResponse != providerResponseBeforeAlignment) {
                    sessionStore.saveTranscriptionResult(sessionId, providerResponse);
                }
            } catch (IllegalArgumentException invalidResponse) {
                long maxProviderEndMs = providerResponseBeforeAlignment.segments().stream()
                        .mapToLong(ProviderTranscriptionResponse.Segment::endMs).max().orElse(-1);
                LOGGER.warn("Transcription response could not be aligned sessionId={} traceId={} meetingDurationMs={} segmentCount={} maxProviderEndMs={} maxOverrunMs={} failureType=TIMING_OUTSIDE_MEETING",
                        sessionId, effectiveTraceId, job.durationMs(),
                        providerResponseBeforeAlignment.segments().size(), maxProviderEndMs,
                        Math.max(0, maxProviderEndMs - job.durationMs()));
                String responseStage = resumingDiarization ? DIARIZATION : TRANSCRIPTION;
                int responseProgress = resumingDiarization ? 66 : 33;
                fail(sessionId, effectiveTraceId, responseStage, responseProgress,
                        "TRANSCRIPTION_RESPONSE_INVALID", false, stageStartedAt);
                return;
            } catch (RuntimeException saveFailure) {
                String responseStage = resumingDiarization ? DIARIZATION : TRANSCRIPTION;
                int responseProgress = resumingDiarization ? 66 : 33;
                fail(sessionId, effectiveTraceId, responseStage, responseProgress,
                        "TRANSCRIPTION_RESULT_SAVE_FAILED", false, stageStartedAt);
                return;
            }
            if (!resumingDiarization) {
                sessionStore.recordProcessingProgress(sessionId, TRANSCRIPTION, 66);
                logStageSucceeded(sessionId, effectiveTraceId, TRANSCRIPTION, stageStartedAt);
            }

            sessionStore.recordProcessingProgress(sessionId, DIARIZATION, 67);
            stageStartedAt = System.nanoTime();
            logStageStarted(sessionId, effectiveTraceId, DIARIZATION);
            TranscriptResult normalized;
            try {
                normalized = diarizationAdapter.normalize(providerResponse, job.durationMs());
                validateResult(normalized, job.durationMs());
            } catch (IllegalArgumentException invalidResult) {
                fail(sessionId, effectiveTraceId, DIARIZATION, 66,
                        "DIARIZATION_INVALID_RESULT", false, stageStartedAt);
                return;
            } catch (RuntimeException failure) {
                fail(sessionId, effectiveTraceId, DIARIZATION, 66, "DIARIZATION_FAILED", true, stageStartedAt);
                return;
            }
            try {
                sessionStore.saveDiarizationResult(sessionId, normalized.speakers(), normalized.segments());
                sessionStore.removeTranscriptionResult(sessionId);
            } catch (RuntimeException saveFailure) {
                fail(sessionId, effectiveTraceId, DIARIZATION, 66,
                        "DIARIZATION_RESULT_SAVE_FAILED", false, stageStartedAt);
                return;
            }
            logStageSucceeded(sessionId, effectiveTraceId, DIARIZATION, stageStartedAt);
        }

        sessionStore.recordProcessingProgress(sessionId, MINUTES_GENERATION, 100);
        stageStartedAt = System.nanoTime();
        logStageStarted(sessionId, effectiveTraceId, MINUTES_GENERATION);
        StructuredMinutes minutes;
        try {
            MeetingSession current = sessionStore.find(sessionId).orElseThrow();
            minutes = minutesGeneration.generate(current);
        } catch (MinutesGenerationException failure) {
            String code = failure.code() == null ? "MINUTES_PROCESSING_FAILED" : "MINUTES_" + failure.code().name();
            fail(sessionId, effectiveTraceId, MINUTES_GENERATION, 100,
                    safeCode(code, "MINUTES_PROCESSING_FAILED"), failure.retryable(), stageStartedAt);
            return;
        } catch (RuntimeException failure) {
            fail(sessionId, effectiveTraceId, MINUTES_GENERATION, 100,
                    "MINUTES_PROCESSING_FAILED", false, stageStartedAt);
            return;
        }
        try {
            sessionStore.saveMinutesAndComplete(sessionId, minutes);
        } catch (RuntimeException saveFailure) {
            fail(sessionId, effectiveTraceId, MINUTES_GENERATION, 100,
                    "MINUTES_RESULT_SAVE_FAILED", false, stageStartedAt);
            return;
        }
        try {
            audioAssembly.deleteAudio(sessionId);
        } catch (RuntimeException cleanupFailure) {
            LOGGER.atWarn().addKeyValue("sessionId", sessionId).addKeyValue("traceId", effectiveTraceId)
                    .addKeyValue("stage", MINUTES_GENERATION).addKeyValue("errorCode", "AUDIO_CLEANUP_FAILED")
                    .log("Private Audio cleanup will be retried by expiry cleanup.");
            try {
                audioAssembly.retainAudioUntil(sessionId, clock.instant().plusSeconds(24 * 60 * 60));
            } catch (RuntimeException expiryFailure) {
                LOGGER.atError().addKeyValue("sessionId", sessionId).addKeyValue("traceId", effectiveTraceId)
                        .addKeyValue("stage", MINUTES_GENERATION).addKeyValue("errorCode", "AUDIO_CLEANUP_FAILED")
                        .log("Private Audio cleanup scheduling failed.");
            }
        }
        logStageSucceeded(sessionId, effectiveTraceId, MINUTES_GENERATION, stageStartedAt);
    }

    private void fail(String sessionId, String traceId, String stage, int lastCompletedProgress,
            String errorCode, boolean retryable, long stageStartedAt) {
        fail(sessionId, traceId, stage, lastCompletedProgress, errorCode, retryable, stageStartedAt,
                !AUDIO_ASSEMBLY.equals(stage));
    }

    private void fail(String sessionId, String traceId, String stage, int lastCompletedProgress,
            String errorCode, boolean retryable, long stageStartedAt, boolean retryInputAvailable) {
        String safeErrorCode = safeCode(errorCode, "PROCESSING_FAILED");
        boolean audioAvailable = !AUDIO_ASSEMBLY.equals(stage);
        boolean hasRetryInput = retryInputAvailable && retryable;
        Instant failedAt = clock.instant();
        Instant expiresAt = failedAt.plusSeconds(24 * 60 * 60);
        try {
            audioAssembly.retainAudioUntil(sessionId, expiresAt);
        } catch (RuntimeException retentionFailure) {
            audioAvailable = false;
            hasRetryInput = false;
            safeErrorCode = "AUDIO_STORAGE_FAILED";
            retryable = false;
            expiresAt = null;
        }
        ProcessingFailure failure = new ProcessingFailure(stage, safeErrorCode, retryable, audioAvailable,
                expiresAt, false, hasRetryInput);
        sessionStore.failProcessing(sessionId, failure.stage(), lastCompletedProgress,
                failure.errorCode(), failure.retryable(), failure.audioAvailable(), failure.retryInputAvailable(),
                failure.audioExpiresAt());
        FailureContext context = FailureContext.from(sessionId, stage, traceId, safeErrorCode, retryable,
                failedAt).orElse(null);
        if (context != null) {
            failureContextReporter.report(context, LOGGER);
        } else {
            LOGGER.atWarn()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("traceId", FailureContext.correlationId(traceId))
                    .addKeyValue("stage", stage)
                    .addKeyValue("errorCode", safeErrorCode)
                    .addKeyValue("retryable", retryable)
                    .log("Processing failed with safe fallback.");
        }
    }

    private static void validateResult(TranscriptResult result, long meetingDurationMs) {
        if (result == null || meetingDurationMs < 0) throw invalidResult();
        Set<String> speakerIds = new HashSet<>();
        for (Speaker speaker : result.speakers()) {
            if (speaker == null || isBlank(speaker.speakerId()) || isBlank(speaker.label())
                    || !speakerIds.add(speaker.speakerId())) {
                throw invalidResult();
            }
        }
        Set<String> segmentIds = new HashSet<>();
        for (TranscriptSegment segment : result.segments()) {
            if (segment == null || isBlank(segment.segmentId()) || !segmentIds.add(segment.segmentId())
                    || !speakerIds.contains(segment.speakerId()) || segment.startMs() < 0
                    || segment.endMs() < segment.startMs() || segment.endMs() > meetingDurationMs
                    || isBlank(segment.text())) {
                throw invalidResult();
            }
        }
    }

    private static IllegalArgumentException invalidResult() {
        return new IllegalArgumentException("Diarization result is invalid.");
    }

    private static String safeCode(String code, String fallback) {
        return code != null && code.matches("[A-Z][A-Z0-9_]*") ? code : fallback;
    }

    private static boolean isRetryableAssemblyFailure(String code) {
        return "AUDIO_ASSEMBLY_FAILED".equals(code) || "AUDIO_STORAGE_FAILED".equals(code);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void logStageStarted(String sessionId, String traceId, String stage) {
        LOGGER.atInfo()
                .addKeyValue("sessionId", sessionId)
                .addKeyValue("traceId", traceId)
                .addKeyValue("stage", stage)
                .log("Processing stage started.");
    }

    private static void logStageSucceeded(String sessionId, String traceId, String stage, long startedAt) {
        LOGGER.atInfo()
                .addKeyValue("sessionId", sessionId)
                .addKeyValue("traceId", traceId)
                .addKeyValue("stage", stage)
                .addKeyValue("durationMs", elapsedMillis(startedAt))
                .log("Processing stage succeeded.");
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedAt));
    }
}
