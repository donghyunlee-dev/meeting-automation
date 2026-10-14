package com.meetingautomation.api.error;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/** Safe, correlated metadata for operational failures. Provider exceptions and payloads are never accepted. */
public final class FailureContext {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Set<String> AUDIO_ASSEMBLY_CODES = Set.of(
            "AUDIO_ASSEMBLY_FAILED", "AUDIO_STORAGE_FAILED", "AUDIO_ASSEMBLY_INVALID_INPUT",
            "AUDIO_ASSEMBLY_OUTPUT_INVALID", "AUDIO_ASSEMBLY_OUTPUT_MISMATCH", "AUDIO_ASSEMBLY_EMPTY",
            "AUDIO_ASSEMBLY_CHUNKS_INCOMPLETE", "AUDIO_ASSEMBLY_CHUNK_INVALID", "AUDIO_ASSEMBLY_SIZE_LIMIT_EXCEEDED");
    private static final Set<String> TRANSCRIPTION_CODES = Set.of(
            "TRANSCRIPTION_FAILED", "TRANSCRIPTION_INVALID_RESPONSE", "TRANSCRIPTION_PROCESSING_FAILED",
            "TRANSCRIPTION_INVALID_AUDIO", "TRANSCRIPTION_CONFIGURATION_ERROR", "TRANSCRIPTION_AUTHENTICATION_FAILED");
    private static final Set<String> DIARIZATION_CODES = Set.of(
            "DIARIZATION_FAILED", "DIARIZATION_INVALID_RESULT", "DIARIZATION_RESULT_SAVE_FAILED");
    private static final Set<String> MINUTES_CODES = Set.of(
            "MINUTES_PROCESSING_FAILED", "MINUTES_CONFIGURATION_ERROR", "MINUTES_AUTHENTICATION_FAILED",
            "MINUTES_RESULT_SAVE_FAILED");

    private final IncidentType incidentType;
    private final String sessionId;
    private final String stage;
    private final String traceId;
    private final String errorCode;
    private final boolean retryable;
    private final String safeMessage;
    private final Instant occurredAt;

    private FailureContext(IncidentType incidentType, String sessionId, String stage, String traceId,
            String errorCode, boolean retryable, Instant occurredAt) {
        this.incidentType = incidentType;
        this.sessionId = safeIdentifier(sessionId);
        this.stage = stage;
        this.traceId = correlationId(traceId);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.safeMessage = incidentType.safeMessage();
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public static Optional<FailureContext> from(String sessionId, String stage, String traceId,
            String errorCode, boolean retryable, Instant occurredAt) {
        IncidentType type = classify(stage, errorCode);
        if (type == null) return Optional.empty();
        return Optional.of(new FailureContext(type, sessionId, stage, traceId, errorCode, retryable, occurredAt));
    }

    public IncidentType incidentType() { return incidentType; }
    public String sessionId() { return sessionId; }
    public String stage() { return stage; }
    public String traceId() { return traceId; }
    public String errorCode() { return errorCode; }
    public boolean retryable() { return retryable; }
    public String safeMessage() { return safeMessage; }
    public Instant occurredAt() { return occurredAt; }

    public void logWarning(Logger logger) {
        var event = logger.atWarn()
                .addKeyValue("incidentType", incidentType.name())
                .addKeyValue("traceId", traceId)
                .addKeyValue("stage", stage)
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("retryable", retryable)
                .addKeyValue("occurredAt", occurredAt.toString())
                .addKeyValue("safeMessage", safeMessage);
        if (sessionId != null) event.addKeyValue("sessionId", sessionId);
        event.log("Operational failure.");
    }

    public static String correlationId(String candidate) {
        return candidate != null && SAFE_IDENTIFIER.matcher(candidate).matches()
                ? candidate : "tr_" + UUID.randomUUID();
    }

    private static String safeIdentifier(String candidate) {
        return candidate != null && SAFE_IDENTIFIER.matcher(candidate).matches() ? candidate : null;
    }

    private static IncidentType classify(String stage, String errorCode) {
        if (errorCode == null) return null;
        if (stage == null) {
            if (AUDIO_ASSEMBLY_CODES.contains(errorCode) || TRANSCRIPTION_CODES.contains(errorCode)
                    || DIARIZATION_CODES.contains(errorCode) || MINUTES_CODES.contains(errorCode))
                return IncidentType.PROCESSING_FAILURE;
            return switch (errorCode) {
                case "DOCUMENT_FAILED", "PARTICIPANT_LIST_FAILED" -> IncidentType.DOCUMENT_FAILURE;
                case "EMAIL_FAILED" -> IncidentType.EMAIL_FAILURE;
                case "NOTIFICATION_FAILED" -> IncidentType.NOTIFICATION_FAILURE;
                default -> null;
            };
        }
        return switch (stage) {
            case "AUDIO_ASSEMBLY" -> AUDIO_ASSEMBLY_CODES.contains(errorCode) ? IncidentType.PROCESSING_FAILURE : null;
            case "TRANSCRIPTION" -> TRANSCRIPTION_CODES.contains(errorCode) ? IncidentType.PROCESSING_FAILURE : null;
            case "DIARIZATION" -> DIARIZATION_CODES.contains(errorCode) ? IncidentType.PROCESSING_FAILURE : null;
            case "MINUTES_GENERATION" -> MINUTES_CODES.contains(errorCode) ? IncidentType.PROCESSING_FAILURE : null;
            case "DOCUMENT_SAVE" -> Set.of("DOCUMENT_FAILED", "PARTICIPANT_LIST_FAILED").contains(errorCode)
                    ? IncidentType.DOCUMENT_FAILURE : null;
            case "EMAIL_DELIVERY" -> "EMAIL_FAILED".equals(errorCode) ? IncidentType.EMAIL_FAILURE : null;
            case "NOTIFICATION" -> "NOTIFICATION_FAILED".equals(errorCode) ? IncidentType.NOTIFICATION_FAILURE : null;
            default -> null;
        };
    }

    @Override
    public String toString() {
        return "FailureContext[incidentType=" + incidentType + ", sessionId=" + sessionId + ", stage=" + stage
                + ", traceId=" + traceId + ", errorCode=" + errorCode + ", retryable=" + retryable
                + ", safeMessage=" + safeMessage + ", occurredAt=" + occurredAt + "]";
    }

    public enum IncidentType {
        PROCESSING_FAILURE("Meeting processing failed."),
        DOCUMENT_FAILURE("Meeting document could not be saved."),
        EMAIL_FAILURE("Email delivery failed."),
        NOTIFICATION_FAILURE("Meeting notification failed.");

        private final String safeMessage;

        IncidentType(String safeMessage) { this.safeMessage = safeMessage; }
        public String safeMessage() { return safeMessage; }
    }
}
