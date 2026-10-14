package com.meetingautomation.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.stream.Stream;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

class FailureContextTests {
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-14T10:00:00Z");

    @ParameterizedTest
    @MethodSource("businessFailures")
    void mapsKnownBusinessFailuresToSafeIncidentContext(
            String stage, String code, FailureContext.IncidentType incidentType, boolean retryable) {
        FailureContext context = FailureContext.from(
                "session-42", stage, "trace-42", code, retryable, OCCURRED_AT).orElseThrow();

        assertThat(context.incidentType()).isEqualTo(incidentType);
        assertThat(context.sessionId()).isEqualTo("session-42");
        assertThat(context.stage()).isEqualTo(stage);
        assertThat(context.traceId()).isEqualTo("trace-42");
        assertThat(context.errorCode()).isEqualTo(code);
        assertThat(context.retryable()).isEqualTo(retryable);
        assertThat(context.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(context.safeMessage()).isNotBlank();
        assertThat(context.toString()).doesNotContain("secret", "provider body", "minutes text");
    }

    static Stream<Arguments> businessFailures() {
        return Stream.of(
                Arguments.of("AUDIO_ASSEMBLY", "AUDIO_ASSEMBLY_FAILED", FailureContext.IncidentType.PROCESSING_FAILURE, true),
                Arguments.of("TRANSCRIPTION", "TRANSCRIPTION_FAILED", FailureContext.IncidentType.PROCESSING_FAILURE, false),
                Arguments.of("DIARIZATION", "DIARIZATION_FAILED", FailureContext.IncidentType.PROCESSING_FAILURE, false),
                Arguments.of("MINUTES_GENERATION", "MINUTES_PROCESSING_FAILED", FailureContext.IncidentType.PROCESSING_FAILURE, true),
                Arguments.of("DOCUMENT_SAVE", "DOCUMENT_FAILED", FailureContext.IncidentType.DOCUMENT_FAILURE, true),
                Arguments.of("EMAIL_DELIVERY", "EMAIL_FAILED", FailureContext.IncidentType.EMAIL_FAILURE, false),
                Arguments.of("NOTIFICATION", "NOTIFICATION_FAILED", FailureContext.IncidentType.NOTIFICATION_FAILURE, false));
    }

    @ParameterizedTest
    @MethodSource("nonIncidentFailures")
    void excludesValidationLookupAndConflictFailuresFromOperationalIncidents(String stage, String code) {
        assertThat(FailureContext.from("session-42", stage, "trace-42", code, false, OCCURRED_AT)).isEmpty();
    }

    static Stream<Arguments> nonIncidentFailures() {
        return Stream.of(
                Arguments.of("AUDIO_ASSEMBLY", "VALIDATION_FAILED"),
                Arguments.of("DOCUMENT_SAVE", "MEETING_NOT_FOUND"),
                Arguments.of("EMAIL_DELIVERY", "SESSION_STATE_CONFLICT"),
                Arguments.of("NOTIFICATION", "IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void unknownOrMismatchedFailureUsesSafeFallbackOrDoesNotCreateAnIncident() {
        assertThat(FailureContext.from("session-42", "EMAIL_DELIVERY", "trace-42",
                "private provider text", true, OCCURRED_AT)).isEmpty();
        assertThat(FailureContext.from("session-42", "UNKNOWN_STAGE", "trace-42",
                "DOCUMENT_FAILED", true, OCCURRED_AT)).isEmpty();
        assertThat(FailureContext.from("session-42", "EMAIL_DELIVERY", "trace-42",
                "DOCUMENT_FAILED", true, OCCURRED_AT)).isEmpty();
    }

    @Test
    void missingCorrelationAndTimeAreGeneratedAndSafe() {
        FailureContext context = FailureContext.from(null, "TRANSCRIPTION", null,
                "TRANSCRIPTION_FAILED", true, null).orElseThrow();

        assertThat(context.sessionId()).isNull();
        assertThat(context.traceId()).startsWith("tr_");
        assertThat(context.occurredAt()).isNotNull();
        assertThat(context.safeMessage()).doesNotContain("TRANSCRIPTION_FAILED");
    }

    @Test
    void structuredIncidentLogContainsOnlySafeCorrelationFields() {
        FailureContext context = FailureContext.from("ms_safe_42", "EMAIL_DELIVERY", "request-42",
                "EMAIL_FAILED", false, OCCURRED_AT).orElseThrow();
        Logger logger = (Logger) LoggerFactory.getLogger("failure-context-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        context.logWarning(logger);

        String logs = appender.list.stream()
                .map(event -> event.getFormattedMessage() + event.getKeyValuePairs())
                .reduce("", String::concat);
        logger.detachAppender(appender);
        appender.stop();
        assertThat(logs).contains("EMAIL_FAILURE", "ms_safe_42", "request-42", "EMAIL_DELIVERY",
                "EMAIL_FAILED", "retryable=\"false\"", "occurredAt", "Email delivery failed.")
                .doesNotContain("provider body", "webhook", "secret", "email@example");
    }
}
