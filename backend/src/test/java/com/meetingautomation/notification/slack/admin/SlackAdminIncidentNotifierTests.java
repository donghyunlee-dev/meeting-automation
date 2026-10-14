package com.meetingautomation.notification.slack.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.meetingautomation.api.error.FailureContext;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SlackAdminIncidentNotifierTests {
    private static final String ADMIN_WEBHOOK = "https://hooks.slack.com/services/T-ADMIN/B-ADMIN/admin-secret";
    private static final FailureContext CONTEXT = FailureContext.from("ms_admin_1", "EMAIL_DELIVERY",
            "tr_admin_1", "EMAIL_FAILED", false, Instant.parse("2026-10-14T10:00:00Z")).orElseThrow();

    @Test
    void postsOneSafePlainTextMessageToAdminUrlAndAcceptsSlackOk() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ADMIN_WEBHOOK)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(content().json("""
                        {"text":"Incident: EMAIL_FAILURE\\nSession: ms_admin_1\\nStage: EMAIL_DELIVERY\\nTrace: tr_admin_1\\nMessage: Email delivery failed.\\nRetryable: false\\nOccurred at: 2026-10-14T10:00:00Z","mrkdwn":false,"unfurl_links":false}
                        """))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        var notifier = new SlackAdminIncidentNotifier(builder, new SlackAdminIncidentSettings(ADMIN_WEBHOOK));
        notifier.notifyIncident(CONTEXT);

        server.verify();
    }

    @Test
    void absentOrInvalidAdminUrlDoesNotSendAnHttpRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var absent = new SlackAdminIncidentNotifier(builder, new SlackAdminIncidentSettings(""));
        var invalid = new SlackAdminIncidentNotifier(builder,
                new SlackAdminIncidentSettings("http://hooks.slack.com/services/T/B/secret"));

        assertThatCode(() -> absent.notifyIncident(CONTEXT)).doesNotThrowAnyException();
        assertThatCode(() -> invalid.notifyIncident(CONTEXT)).doesNotThrowAnyException();

        server.verify();
    }

    @ParameterizedTest
    @MethodSource("nonSuccessResponses")
    void refusalsAndAmbiguousResponsesAreBestEffortSingleAttempts(HttpStatus status, String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicInteger requestCount = new AtomicInteger();
        server.expect(requestTo(ADMIN_WEBHOOK)).andExpect(method(HttpMethod.POST)).andRespond(request -> {
            requestCount.incrementAndGet();
            return withStatus(status).body(body).createResponse(request);
        });

        var notifier = new SlackAdminIncidentNotifier(builder, new SlackAdminIncidentSettings(ADMIN_WEBHOOK));
        assertThatCode(() -> notifier.notifyIncident(CONTEXT)).doesNotThrowAnyException();

        server.verify();
        org.assertj.core.api.Assertions.assertThat(requestCount).hasValue(1);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> nonSuccessResponses() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(HttpStatus.BAD_REQUEST, "private refusal body"),
                org.junit.jupiter.params.provider.Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, "private server body"),
                org.junit.jupiter.params.provider.Arguments.of(HttpStatus.OK, "not_ok"));
    }

    @Test
    void transportFailureDoesNotThrowOrRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicInteger requestCount = new AtomicInteger();
        server.expect(requestTo(ADMIN_WEBHOOK)).andRespond(request -> {
            requestCount.incrementAndGet();
            throw new IOException("private transport exception text");
        });

        var notifier = new SlackAdminIncidentNotifier(builder, new SlackAdminIncidentSettings(ADMIN_WEBHOOK));
        assertThatCode(() -> notifier.notifyIncident(CONTEXT)).doesNotThrowAnyException();

        server.verify();
        org.assertj.core.api.Assertions.assertThat(requestCount).hasValue(1);
    }
}
