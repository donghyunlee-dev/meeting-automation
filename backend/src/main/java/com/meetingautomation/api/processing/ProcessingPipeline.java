package com.meetingautomation.api.processing;

import com.meetingautomation.api.audio.AudioAssemblyStorage;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.ProcessingFailure;
import com.meetingautomation.api.session.UploadPolicy;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.TranscriptResult;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.transcription.DiarizationResultAdapter;
import com.meetingautomation.transcription.ProviderTranscriptionResponse;
import com.meetingautomation.transcription.TranscriptionCommand;
import com.meetingautomation.transcription.TranscriptionException;
import com.meetingautomation.transcription.TranscriptionProvider;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs the ordered audio assembly, transcription, and diarization stages for one processing job. */
@Service
public class ProcessingPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingPipeline.class);
    private static final String AUDIO_ASSEMBLY = "AUDIO_ASSEMBLY";
    private static final String TRANSCRIPTION = "TRANSCRIPTION";
    private static final String DIARIZATION = "DIARIZATION";

    private final MeetingSessionStore sessionStore;
    private final AudioAssemblyUseCase audioAssembly;
    private final TranscriptionProvider transcriptionProvider;
    private final DiarizationResultAdapter diarizationAdapter;

    public ProcessingPipeline(MeetingSessionStore sessionStore, AudioAssemblyUseCase audioAssembly,
            TranscriptionProvider transcriptionProvider, DiarizationResultAdapter diarizationAdapter) {
        this.sessionStore = sessionStore;
        this.audioAssembly = audioAssembly;
        this.transcriptionProvider = transcriptionProvider;
        this.diarizationAdapter = diarizationAdapter;
    }

    public void process(ProcessingJob job, String traceId) {
        if (job == null || job.sessionId() == null || job.sessionId().isBlank()) return;
        String sessionId = job.sessionId();
        String effectiveTraceId = traceId == null || traceId.isBlank() ? "tr_" + UUID.randomUUID() : traceId;
        MeetingSession session = sessionStore.find(sessionId).orElse(null);
        if (session == null || !"PROCESSING".equals(session.status())) return;

        UploadPolicy policy = sessionStore.findUploadPolicy(sessionId).orElse(null);
        long stageStartedAt = System.nanoTime();
        logStageStarted(sessionId, effectiveTraceId, AUDIO_ASSEMBLY);
        AudioAssemblyStorage.Assembly assembly;
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
                    safeCode(failure.code(), "AUDIO_ASSEMBLY_FAILED"), isRetryableAssemblyFailure(failure.code()),
                    stageStartedAt);
            return;
        } catch (RuntimeException failure) {
            fail(sessionId, effectiveTraceId, AUDIO_ASSEMBLY, 0, "AUDIO_ASSEMBLY_FAILED", true, stageStartedAt);
            return;
        }
        sessionStore.recordProcessingProgress(sessionId, AUDIO_ASSEMBLY, 33);
        logStageSucceeded(sessionId, effectiveTraceId, AUDIO_ASSEMBLY, stageStartedAt);

        sessionStore.recordProcessingProgress(sessionId, TRANSCRIPTION, 34);
        stageStartedAt = System.nanoTime();
        logStageStarted(sessionId, effectiveTraceId, TRANSCRIPTION);
        ProviderTranscriptionResponse providerResponse;
        try {
            TranscriptionCommand command = new TranscriptionCommand(
                    assembly.reference(), assembly.mimeType(), null, job.durationMs());
            providerResponse = transcriptionProvider.transcribeWithDiarization(command);
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
        sessionStore.recordProcessingProgress(sessionId, TRANSCRIPTION, 66);
        logStageSucceeded(sessionId, effectiveTraceId, TRANSCRIPTION, stageStartedAt);

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
            fail(sessionId, effectiveTraceId, DIARIZATION, 66, "DIARIZATION_FAILED", false, stageStartedAt);
            return;
        }
        try {
            sessionStore.saveDiarizationResult(sessionId, normalized.speakers(), normalized.segments());
        } catch (RuntimeException saveFailure) {
            fail(sessionId, effectiveTraceId, DIARIZATION, 66,
                    "DIARIZATION_RESULT_SAVE_FAILED", false, stageStartedAt);
            return;
        }
        logStageSucceeded(sessionId, effectiveTraceId, DIARIZATION, stageStartedAt);
    }

    private void fail(String sessionId, String traceId, String stage, int lastCompletedProgress,
            String errorCode, boolean retryable, long stageStartedAt) {
        String safeErrorCode = safeCode(errorCode, "PROCESSING_FAILED");
        ProcessingFailure failure = new ProcessingFailure(stage, safeErrorCode, retryable);
        sessionStore.failProcessing(sessionId, failure.stage(), lastCompletedProgress,
                failure.errorCode(), failure.retryable());
        LOGGER.atWarn()
                .addKeyValue("sessionId", sessionId)
                .addKeyValue("traceId", traceId)
                .addKeyValue("stage", stage)
                .addKeyValue("durationMs", elapsedMillis(stageStartedAt))
                .addKeyValue("errorCode", safeErrorCode)
                .addKeyValue("retryable", retryable)
                .log("Processing stage failed.");
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
