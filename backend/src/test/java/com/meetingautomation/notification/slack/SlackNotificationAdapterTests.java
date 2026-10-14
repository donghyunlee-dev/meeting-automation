package com.meetingautomation.notification.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.meetingautomation.notification.DeliveryResult;
import com.meetingautomation.notification.MeetingPublishedCommand;
import com.meetingautomation.api.health.IntegrationHealthStatus;
import java.net.URI;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SlackNotificationAdapterTests {
    private static final String WEBHOOK = "https://hooks.slack.com/services/T000/B000/secret-token";
    private static final MeetingPublishedCommand COMMAND = new MeetingPublishedCommand(
            "Planning <@U123>\n<!channel>", OffsetDateTime.parse("2026-10-14T10:30:00+09:00"),
            "https://docs.example.test/meeting/1");

    @Test
    void postsPlainTextPayloadToConfiguredWebhookAndMapsOkToSent() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URI.create(WEBHOOK))).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(content().json("{\"text\":\"Meeting published: Planning <@U123> <!channel>\\nMeeting date: 2026-10-14T10:30+09:00\\nDocument: https://docs.example.test/meeting/1\",\"mrkdwn\":false,\"unfurl_links\":false}"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        var adapter = new SlackNotificationAdapter(builder, new SlackNotificationSettings("SLACK", WEBHOOK));
        assertThat(adapter.validateConnection()).isEqualTo(new IntegrationHealthStatus(true, false));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.sent());
        server.verify();
        assertThat(adapter.validateConnection().reachable()).isTrue();
    }

    @Test
    void reportsUnconfiguredWithoutMakingHttpRequest() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new SlackNotificationAdapter(builder,
                new SlackNotificationSettings("SLACK", "http://hooks.slack.com/services/T/B/secret"));
        assertThat(adapter.validateConnection()).isEqualTo(new IntegrationHealthStatus(false, false));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(true));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://hooks.slack.com/services/T/B/secret",
            "https://example.test/services/T/B/secret",
            "https://hooks.slack.com/other/T/B/secret",
            "https://user@hooks.slack.com/services/T/B/secret",
            "https://hooks.slack.com:8443/services/T/B/secret",
            "https://hooks.slack.com/services/T/B/secret?redirect=evil.test"
    })
    void rejectsWebhookUrlsOutsideExpectedHttpsHostAndPathWithoutRequests(String url) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new SlackNotificationAdapter(builder, new SlackNotificationSettings("SLACK", url));
        assertThat(adapter.validateConnection()).isEqualTo(new IntegrationHealthStatus(false, false));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(true));
        server.verify();
    }

    @Test
    void mapsRateLimitAsRetryableAndMalformedPayloadAsPermanentWithoutExposingResponseBody() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("secret provider response").header("Retry-After", "10"));
        server.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("action_prohibited"));
        server.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .body("secret invalid webhook"));
        server.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("secret malformed details"));
        server.expect(requestTo(WEBHOOK)).andRespond(withSuccess("not_ok", MediaType.TEXT_PLAIN));
        server.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("secret server details"));
        var adapter = new SlackNotificationAdapter(builder, new SlackNotificationSettings("SLACK", WEBHOOK));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(true));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(true));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(true));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(false));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(false));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(false));
        assertThat(adapter.validateConnection()).isEqualTo(new IntegrationHealthStatus(true, false));
        server.verify();
    }

    @Test
    void transportFailureIsNotRetriedAndDoesNotMarkProviderReachable() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(WEBHOOK)).andRespond(request -> { throw new java.io.IOException("secret transport detail"); });
        var adapter = new SlackNotificationAdapter(builder, new SlackNotificationSettings("SLACK", WEBHOOK));
        assertThat(adapter.sendMeetingPublished(COMMAND)).isEqualTo(DeliveryResult.failed(false));
        assertThat(adapter.validateConnection()).isEqualTo(new IntegrationHealthStatus(true, false));
        server.verify();
    }
}
