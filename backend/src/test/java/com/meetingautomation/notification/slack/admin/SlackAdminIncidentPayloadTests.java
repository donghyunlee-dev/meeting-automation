package com.meetingautomation.notification.slack.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingautomation.api.error.FailureContext;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class SlackAdminIncidentPayloadTests {
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-14T10:00:00Z");

    @ParameterizedTest
    @MethodSource("incidentContexts")
    void serializesOnlySafePlainTextForEveryIncidentType(
            String stage, String code, FailureContext.IncidentType type) {
        FailureContext context = FailureContext.from("ms_safe_123", stage, "tr_safe_123", code,
                true, OCCURRED_AT).orElseThrow();

        String json = JsonMapper.builder().build().writeValueAsString(SlackAdminIncidentPayload.from(context));

        assertThat(json).contains(type.name(), "ms_safe_123", stage, "tr_safe_123",
                "Retryable: true", OCCURRED_AT.toString(), context.safeMessage())
                .doesNotContain(code, "audio", "transcript", "minutes", "secret", "recipient@example.test")
                .contains("\"mrkdwn\":false", "\"unfurl_links\":false")
                .doesNotContain("\"sessionId\"", "\"stage\"", "\"errorCode\"", "\"webhookUrl\"");
    }

    static Stream<Arguments> incidentContexts() {
        return Stream.of(
                Arguments.of("TRANSCRIPTION", "TRANSCRIPTION_FAILED", FailureContext.IncidentType.PROCESSING_FAILURE),
                Arguments.of("DOCUMENT_SAVE", "DOCUMENT_FAILED", FailureContext.IncidentType.DOCUMENT_FAILURE),
                Arguments.of("EMAIL_DELIVERY", "EMAIL_FAILED", FailureContext.IncidentType.EMAIL_FAILURE),
                Arguments.of("NOTIFICATION", "NOTIFICATION_FAILED", FailureContext.IncidentType.NOTIFICATION_FAILURE));
    }

    @Test
    void omitsOptionalSessionAndStageAndDoesNotIncludeErrorCode() {
        FailureContext context = FailureContext.from(null, null, "tr_safe_123", "EMAIL_FAILED", false, OCCURRED_AT)
                .orElseThrow();

        String text = SlackAdminIncidentPayload.from(context).text();

        assertThat(text).contains("Incident: EMAIL_FAILURE", "Trace: tr_safe_123", "Retryable: false",
                "Email delivery failed.", OCCURRED_AT.toString())
                .doesNotContain("Session:", "Stage:", "EMAIL_FAILED", "recipient@example.test");
    }

    @Test
    void contextIdentifiersCannotInjectSlackMarkupOrControlLines() {
        FailureContext context = FailureContext.from("ms_safe_123", "EMAIL_DELIVERY",
                "trace-line-break", "EMAIL_FAILED", true, OCCURRED_AT).orElseThrow();

        String text = SlackAdminIncidentPayload.from(context).text();

        assertThat(text).doesNotContain("<@", "<!channel>", "\r", "\u0000");
    }
}
