package com.meetingautomation.email.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.meetingautomation.email.EmailSettings;
import com.meetingautomation.email.MeetingEmailCommand;
import com.meetingautomation.email.MeetingEmailCommand.Recipient;
import com.meetingautomation.email.RecipientDeliveryResult;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class GmailEmailAdapterTests {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private static final String SCOPE = "https://www.googleapis.com/auth/gmail.send";
    private static final EmailSettings SETTINGS = new EmailSettings("GMAIL_API", "synthetic-client-id",
            "synthetic-client-secret", "synthetic-refresh-token", "sender@example.test");
    private static final String ACCESS_TOKEN = "synthetic-access-token";
    private static final String PRIVATE_BODY = "Private meeting summary. 검토 완료\n<html>plain text</html>";
    private static final String DOCUMENT_URL = "https://documents.example.test/saved/page?original=1";

    @Test
    void emptyRecipientsReturnEmptyWithoutCheckingConfigurationOrCallingGoogle() {
        var client = client(new EmailSettings("GMAIL_API", "", "", "", ""));
        assertThat(client.adapter.sendMeetingEmail(command(List.of()))).isEmpty();
        client.server.verify();
    }

    @Test
    void sendsOneUtf8MimeMessagePerSelectedRecipientWithExactSubjectBodyAndDocumentUrl() {
        var client = client(SETTINGS);
        expectToken(client);
        expectSend(client, "first@example.test");
        expectSend(client, "second@example.test");
        var results = client.adapter.sendMeetingEmail(command(List.of(
                new Recipient("pt_1", " first@example.test "), new Recipient("pt_2", "second@example.test"))));
        assertThat(results).containsExactly(RecipientDeliveryResult.sent("pt_1"), RecipientDeliveryResult.sent("pt_2"));
        client.server.verify();
    }

    @Test
    void invalidRecipientFailsIndependentlyAndDoesNotPreventLaterValidRecipient() {
        var client = client(SETTINGS);
        expectToken(client);
        expectSend(client, "valid@example.test");
        var results = client.adapter.sendMeetingEmail(command(List.of(
                new Recipient("pt_bad", "one@example.test,two@example.test"),
                new Recipient("pt_ok", "valid@example.test"))));
        assertThat(results).containsExactly(RecipientDeliveryResult.failed("pt_bad", true),
                RecipientDeliveryResult.sent("pt_ok"));
        client.server.verify();
    }

    @Test
    void oversizedLocalPartAndDomainLabelFailWhileMaximumLocalPartIsSent() {
        var client = client(SETTINGS);
        expectToken(client);
        expectSend(client, "a".repeat(64) + "@example.test");
        assertThat(client.adapter.sendMeetingEmail(command(List.of(
                new Recipient("pt_long_local", "a".repeat(65) + "@example.test"),
                new Recipient("pt_long_domain", "a@" + "b".repeat(64) + ".test"),
                new Recipient("pt_valid", "a".repeat(64) + "@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_long_local", true),
                        RecipientDeliveryResult.failed("pt_long_domain", true), RecipientDeliveryResult.sent("pt_valid"));
        client.server.verify();
    }

    @Test
    void laterFailureDoesNotOverwriteOrRepeatAnAlreadySuccessfulRecipient() {
        var client = client(SETTINGS);
        expectToken(client);
        expectSend(client, "first@example.test");
        client.server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatusCode.valueOf(503))
                .body("private-provider-body"));
        expectSend(client, "third@example.test");
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test"),
                new Recipient("pt_2", "second@example.test"), new Recipient("pt_3", "third@example.test")))))
                .containsExactly(RecipientDeliveryResult.sent("pt_1"), RecipientDeliveryResult.failed("pt_2", false),
                        RecipientDeliveryResult.sent("pt_3"));
        client.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid", "a@b", "a..b@example.test", ".a@example.test",
            "a.@example.test", "a@example.test\r\nBcc: hidden@example.test", "Name <a@example.test>",
            "a@-example.test", "a@example.test;hidden@example.test"})
    void invalidAddressIsCorrectableAndNeverReachesOAuthOrSend(String address) {
        var client = client(SETTINGS);
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_bad", address)))))
                .containsExactly(RecipientDeliveryResult.failed("pt_bad", true));
        client.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-document", "", "https://documents.example.test/page\r\nInjected", "file:///local"})
    void rejectsMissingOrInvalidSavedDocumentReferenceBeforeAnyNetworkCall(String url) {
        var client = client(SETTINGS);
        var command = new MeetingEmailCommand("Subject", PRIVATE_BODY, url,
                List.of(new Recipient("pt_1", "first@example.test")));
        assertThat(client.adapter.sendMeetingEmail(command)).containsExactly(RecipientDeliveryResult.failed("pt_1", true));
        client.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Subject\r\nBcc: hidden@example.test", "Subject\nInjected", "Subject\rInjected"})
    void subjectHeaderInjectionFailsSafelyBeforeAnyNetworkCall(String subject) {
        var client = client(SETTINGS);
        assertThat(client.adapter.sendMeetingEmail(new MeetingEmailCommand(subject, PRIVATE_BODY, DOCUMENT_URL,
                List.of(new Recipient("pt_1", "first@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_1", true));
        client.server.verify();
    }

    @Test
    void validateConnectionRefreshesOnlyAndNeverCallsGmailSendOrProfile() {
        var client = client(SETTINGS);
        expectToken(client);
        assertThat(client.adapter.validateConnection()).isTrue();
        client.server.verify();
    }

    @Test
    void healthAlwaysRefreshesEvenWhenSendingHasCachedToken() {
        var client = client(SETTINGS);
        expectToken(client);
        expectSend(client, "first@example.test");
        client.server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatusCode.valueOf(400))
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"private-token-detail\"}"));
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test")))))
                .containsExactly(RecipientDeliveryResult.sent("pt_1"));
        assertThat(client.adapter.validateConnection()).isFalse();
        client.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.readonly"})
    void refusesTokenWithScopeOtherThanExactSendOnly(String scope) {
        var client = client(SETTINGS);
        client.server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(token(scope, 3600), MediaType.APPLICATION_JSON));
        assertThat(client.adapter.validateConnection()).isFalse();
        client.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"access_token\":\"t\",\"expires_in\":0,\"token_type\":\"Bearer\"}",
            "{\"access_token\":\"t\",\"expires_in\":3600,\"token_type\":\"Other\"}", "not-json"})
    void malformedRefreshResponseProducesSafeCorrectableFailureAndNoSend(String body) {
        var client = client(SETTINGS);
        client.server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_1", true));
        client.server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void refreshFailureIsCorrectableForEveryUnprocessedRecipientAndSendsNothing(int status) {
        var client = client(SETTINGS);
        client.server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .body("private-provider-body synthetic-refresh-token"));
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test"),
                new Recipient("pt_2", "second@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_1", true), RecipientDeliveryResult.failed("pt_2", true));
        client.server.verify();
    }

    @Test
    void refreshTimeoutIsRetryableBecauseSendHasNotStarted() {
        var client = client(SETTINGS);
        client.server.expect(requestTo(TOKEN_URL)).andRespond(withException(new SocketTimeoutException("private-timeout")));
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_1", true));
        client.server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429})
    void correctableSendRejectionIsRetryableAndDoesNotBlockLaterRecipient(int status) {
        var client = client(SETTINGS);
        expectToken(client);
        client.server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .body("private-provider-body"));
        expectSend(client, "second@example.test");
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test"),
                new Recipient("pt_2", "second@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_1", true), RecipientDeliveryResult.sent("pt_2"));
        client.server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 503, 504})
    void ambiguousSendStatusIsNotRetryableAndNeverAutomaticallyRepeated(int status) {
        var client = client(SETTINGS);
        expectToken(client);
        client.server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .body("private-provider-body"));
        expectSend(client, "second@example.test");
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test"),
                new Recipient("pt_2", "second@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_1", false), RecipientDeliveryResult.sent("pt_2"));
        client.server.verify();
    }

    @Test
    void sendTimeoutIsNotRetryableAndLaterRecipientIsStillProcessed() {
        var client = client(SETTINGS);
        expectToken(client);
        client.server.expect(requestTo(SEND_URL)).andRespond(withException(new SocketTimeoutException("private-timeout")));
        expectSend(client, "second@example.test");
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test"),
                new Recipient("pt_2", "second@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_1", false), RecipientDeliveryResult.sent("pt_2"));
        client.server.verify();
    }

    @Test
    void tokenIsReusedBeforeExpiryAndRefreshedAtExpiryOnly() {
        var clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new TestClient(new GmailEmailAdapter(builder, SETTINGS, clock), server);
        expectToken(client);
        expectSend(client, "first@example.test");
        expectSend(client, "first@example.test");
        expectToken(client);
        expectSend(client, "first@example.test");
        var command = command(List.of(new Recipient("pt_1", "first@example.test")));
        assertThat(client.adapter.sendMeetingEmail(command)).containsExactly(RecipientDeliveryResult.sent("pt_1"));
        clock.now = clock.now.plusSeconds(3599);
        assertThat(client.adapter.sendMeetingEmail(command)).containsExactly(RecipientDeliveryResult.sent("pt_1"));
        clock.now = clock.now.plusSeconds(1);
        assertThat(client.adapter.sendMeetingEmail(command)).containsExactly(RecipientDeliveryResult.sent("pt_1"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "{\"id\":\"\"}"})
    void malformedSuccessfulSendResponseHasAmbiguousAcceptanceAndIsNotRepeated(String body) {
        var client = client(SETTINGS);
        expectToken(client);
        client.server.expect(requestTo(SEND_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test")))))
                .containsExactly(RecipientDeliveryResult.failed("pt_1", false));
        client.server.verify();
    }

    @Test
    void successAndSafeFailureExposeNoCredentialsAddressMimeOrProviderBody(CapturedOutput output) {
        var client = client(SETTINGS);
        expectToken(client);
        client.server.expect(requestTo(SEND_URL)).andRespond(withStatus(HttpStatusCode.valueOf(403))
                .body("private-provider-body " + ACCESS_TOKEN + " first@example.test"));
        var command = command(List.of(new Recipient("pt_1", "first@example.test")));
        var results = client.adapter.sendMeetingEmail(command);
        assertThat(results).containsExactly(RecipientDeliveryResult.failed("pt_1", true));
        var serialized = JsonMapper.builder().build().writeValueAsString(results);
        assertThat(serialized).isEqualTo("[{\"recipientParticipantId\":\"pt_1\",\"status\":\"FAILED\",\"retryable\":true,\"errorCode\":\"EMAIL_FAILED\"}]");
        assertThat(serialized + results + command + SETTINGS + output.getAll())
                .doesNotContain("synthetic-client", "synthetic-refresh-token", ACCESS_TOKEN,
                        "first@example.test", "sender@example.test", "private-provider-body", PRIVATE_BODY,
                        DOCUMENT_URL, "Authorization", "Bearer", "Content-Type: text/plain");
        client.server.verify();
    }

    @Test
    void springHttpDebugLoggingCannotExposeOutgoingTokensOrMime() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web");
        var oldLevel = logger.getLevel();
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            var client = client(SETTINGS);
            expectToken(client);
            expectSend(client, "first@example.test");
            assertThat(client.adapter.sendMeetingEmail(command(List.of(new Recipient("pt_1", "first@example.test")))))
                    .containsExactly(RecipientDeliveryResult.sent("pt_1"));
            client.server.verify();
            assertThat(captured.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).doesNotContain("synthetic-client", "synthetic-refresh-token",
                            ACCESS_TOKEN, "first@example.test", "sender@example.test", "Bearer", "raw=", PRIVATE_BODY));
        } finally {
            logger.setLevel(oldLevel);
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    private static MeetingEmailCommand command(List<Recipient> recipients) {
        return new MeetingEmailCommand("검토 회의 ✓", PRIVATE_BODY, DOCUMENT_URL, recipients);
    }

    private static void expectToken(TestClient client) {
        client.server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(MultiValueMap.fromSingleValue(java.util.Map.of(
                        "client_id", "synthetic-client-id", "client_secret", "synthetic-client-secret",
                        "refresh_token", "synthetic-refresh-token", "grant_type", "refresh_token"))))
                .andRespond(withSuccess(token(SCOPE, 3600), MediaType.APPLICATION_JSON));
    }

    private static String token(String scope, long expires) {
        return "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expires
                + ",\"scope\":\"" + scope + "\"}";
    }

    private static void expectSend(TestClient client, String recipient) {
        client.server.expect(requestTo(SEND_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    var json = JsonMapper.builder().build().readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(json.size()).isEqualTo(1);
                    String raw = json.path("raw").asText();
                    assertThat(raw).matches("[A-Za-z0-9_-]+");
                    try {
                        var mime = new MimeMessage(Session.getInstance(new Properties()),
                            new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
                        assertThat(mime.getFrom()).extracting(Object::toString).containsExactly("sender@example.test");
                        assertThat(mime.getRecipients(Message.RecipientType.TO)).extracting(Object::toString)
                            .containsExactly(recipient);
                        assertThat(mime.getRecipients(Message.RecipientType.CC)).isNull();
                        assertThat(mime.getRecipients(Message.RecipientType.BCC)).isNull();
                        assertThat(mime.getSubject()).isEqualTo("검토 회의 ✓");
                        assertThat(mime.isMimeType("text/plain")).isTrue();
                        assertThat(mime.getContentType().toLowerCase()).contains("charset=utf-8");
                        assertThat(mime.getContent().toString().replace("\r\n", "\n"))
                            .isEqualTo(PRIVATE_BODY + "\n\n" + DOCUMENT_URL);
                    } catch (jakarta.mail.MessagingException malformedMime) {
                        throw new AssertionError("Outgoing message must be valid MIME", malformedMime);
                    }
                }).andRespond(withSuccess("{\"id\":\"gmail-message-id\",\"threadId\":\"gmail-thread-id\",\"labelIds\":[\"SENT\"]}",
                        MediaType.APPLICATION_JSON));
    }

    private static TestClient client(EmailSettings settings) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new GmailEmailAdapter(builder, settings), server);
    }

    private record TestClient(GmailEmailAdapter adapter, MockRestServiceServer server) { }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
