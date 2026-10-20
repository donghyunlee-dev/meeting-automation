package com.meetingautomation.email.gmail;

import com.meetingautomation.email.EmailProvider;
import com.meetingautomation.email.EmailSettings;
import com.meetingautomation.email.MeetingEmailCommand;
import com.meetingautomation.email.RecipientDeliveryResult;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Gmail HTTP and MIME details stay inside this outbound adapter. No request or provider data is logged. */
public final class GmailEmailAdapter implements EmailProvider {
    private static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    private static final URI SEND_URI = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/send");
    private static final String SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send";
    private final RestClient client;
    private final Supplier<EmailSettings> settings;
    private final Clock clock;
    // Access tokens are held only inside this adapter instance, never persisted or shared externally.
    private String accessToken;
    private Instant expiresAt = Instant.MIN;
    private EmailSettings tokenSettings;

    public GmailEmailAdapter(RestClient.Builder builder, EmailSettings settings) {
        this(builder, settings, Clock.systemUTC());
    }

    public GmailEmailAdapter(RestClient.Builder builder, EmailSettings settings, Clock clock) {
        this(builder, () -> settings, clock);
    }

    public GmailEmailAdapter(RestClient.Builder builder, Supplier<EmailSettings> settings) {
        this(builder, settings, Clock.systemUTC());
    }

    public GmailEmailAdapter(RestClient.Builder builder, Supplier<EmailSettings> settings, Clock clock) {
        this.client = builder.build();
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean validateConnection() {
        return token(true) != null;
    }

    @Override
    public boolean sendTestMessage() {
        EmailSettings current = settings.get();
        if (!current.configured()) return false;
        String accessToken = token(false);
        if (accessToken == null) return false;
        final String raw;
        try {
            var message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress(current.senderAddress().trim()));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(current.senderAddress().trim()));
            message.setSubject("Meeting Automation 테스트 메일", StandardCharsets.UTF_8.name());
            message.setText("Gmail 연결을 확인하기 위해 보낸 테스트 메일입니다.", StandardCharsets.UTF_8.name());
            message.saveChanges();
            var bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (Exception invalidMessage) {
            return false;
        }
        try {
            JsonNode response = client.post().uri(SEND_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(new GmailSendRequest(raw)).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, providerResponse) -> {
                        throw new SafeProviderRejection(false);
                    }).body(JsonNode.class);
            return response != null && response.path("id").isString() && !response.path("id").asText().isBlank();
        } catch (RuntimeException rejectedOrAmbiguous) {
            return false;
        }
    }

    @Override
    public List<RecipientDeliveryResult> sendMeetingEmail(MeetingEmailCommand command) {
        EmailSettings current = settings.get();
        if (command.recipients().isEmpty()) return List.of();
        if (!current.configured() || !validCommand(command)) return failures(command, true);
        List<RecipientDeliveryResult> results = new ArrayList<>();
        for (int index = 0; index < command.recipients().size(); index++) {
            var recipient = command.recipients().get(index);
            if (!EmailSettings.validAddress(recipient.email())) {
                results.add(RecipientDeliveryResult.failed(recipient.participantId(), true));
                continue;
            }
            // An earlier send may outlast the token. Refresh before each recipient, never repeat a prior send.
            String token = token(false);
            if (token == null) {
                command.recipients().subList(index, command.recipients().size()).forEach(pending ->
                        results.add(RecipientDeliveryResult.failed(pending.participantId(), true)));
                break;
            }
            results.add(send(command, recipient, token, current));
        }
        return List.copyOf(results);
    }

    private static List<RecipientDeliveryResult> failures(MeetingEmailCommand command, boolean retryable) {
        return command.recipients().stream()
                .map(recipient -> RecipientDeliveryResult.failed(recipient.participantId(), retryable)).toList();
    }

    private synchronized String token(boolean forceRefresh) {
        EmailSettings current = settings.get();
        if (!current.configured()) {
            clearToken();
            return null;
        }
        if (tokenSettings != null && !tokenSettings.equals(current)) clearToken();
        tokenSettings = current;
        if (!forceRefresh && accessToken != null && clock.instant().isBefore(expiresAt)) return accessToken;
        clearToken();
        tokenSettings = current;
        try {
            var form = new OAuthRefreshForm();
            form.add("client_id", current.clientId().trim());
            form.add("client_secret", current.clientSecret().trim());
            form.add("refresh_token", current.refreshToken().trim());
            form.add("grant_type", "refresh_token");
            // Consent/bootstrap must grant gmail.send alone. Refresh cannot add mailbox/profile scopes.
            JsonNode response = client.post().uri(TOKEN_URI).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON).body(form).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, providerResponse) -> {
                        throw new SafeProviderRejection(false);
                    }).body(JsonNode.class);
            if (response == null || !response.path("access_token").isString()
                    || response.path("access_token").asText().isBlank()
                    || containsLineBreak(response.path("access_token").asText())
                    || !"Bearer".equalsIgnoreCase(response.path("token_type").asText())
                    || !SEND_SCOPE.equals(response.path("scope").asText())
                    || !response.path("expires_in").isIntegralNumber()
                    || response.path("expires_in").asLong() <= 0) return null;
            expiresAt = clock.instant().plusSeconds(response.path("expires_in").asLong());
            accessToken = response.path("access_token").asText();
            return accessToken;
        } catch (RuntimeException unavailableOrMalformedToken) {
            // Never retain a provider exception, response body, token, or credentials in an error result.
            return null;
        }
    }

    private RecipientDeliveryResult send(MeetingEmailCommand command, MeetingEmailCommand.Recipient recipient,
            String token, EmailSettings current) {
        final String raw;
        try {
            raw = mime(command, recipient.email().trim(), current);
        } catch (Exception invalidMessage) {
            return RecipientDeliveryResult.failed(recipient.participantId(), true);
        }
        try {
            JsonNode response = client.post().uri(SEND_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(new GmailSendRequest(raw)).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, providerResponse) -> {
                        int code = providerResponse.getStatusCode().value();
                        throw new SafeProviderRejection(code >= 400 && code < 500 && code != 408);
                    }).body(JsonNode.class);
            if (response == null || !response.path("id").isString() || response.path("id").asText().isBlank()) {
                return RecipientDeliveryResult.failed(recipient.participantId(), false);
            }
            return RecipientDeliveryResult.sent(recipient.participantId());
        } catch (SafeProviderRejection rejected) {
            return RecipientDeliveryResult.failed(recipient.participantId(), rejected.retryable);
        } catch (RuntimeException ambiguousAcceptance) {
            // After starting a send, transport/5xx/malformed outcomes cannot safely be repeated.
            return RecipientDeliveryResult.failed(recipient.participantId(), false);
        }
    }

    private String mime(MeetingEmailCommand command, String recipient, EmailSettings current) throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(current.senderAddress().trim()));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
        message.setSubject(command.subject(), StandardCharsets.UTF_8.name());
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(command.textBody() + "\n\n" + command.documentUrl(), StandardCharsets.UTF_8.name());
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody(command), "text/html; charset=UTF-8");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(htmlPart);
        message.setContent(alternative);
        message.saveChanges();
        var bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    private static String htmlBody(MeetingEmailCommand command) {
        String safeUrl = command.documentUrl().replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
        return "<!doctype html><html><body style=\"margin:0;padding:24px;background:#f5f6f8;"
                + "font-family:Arial,'Malgun Gothic',sans-serif;color:#202938;line-height:1.65;\">"
                + "<main style=\"max-width:720px;margin:0 auto;padding:28px;background:#ffffff;\">"
                + new EmailMarkdownRenderer().render(command.textBody())
                + "<p style=\"margin-top:28px;padding-top:16px;border-top:1px solid #e5e7eb;\"><a href=\""
                + safeUrl + "\">회의록 문서 열기</a></p></main></body></html>";
    }

    private static boolean validCommand(MeetingEmailCommand command) {
        if (command.subject() == null || containsLineBreak(command.subject()) || command.textBody() == null
                || command.documentUrl() == null || command.documentUrl().isBlank()) return false;
        try {
            var url = URI.create(command.documentUrl());
            return ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    && url.getHost() != null;
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private void clearToken() {
        accessToken = null;
        expiresAt = Instant.MIN;
    }

    private static final class SafeProviderRejection extends RuntimeException {
        private final boolean retryable;

        private SafeProviderRejection(boolean retryable) {
            super("Email provider rejected the request.");
            this.retryable = retryable;
        }
    }

    /** Spring logs request-body string representations at DEBUG/TRACE before message conversion. */
    private static final class OAuthRefreshForm extends LinkedMultiValueMap<String, String> {
        @Override
        public String toString() {
            return "OAuthRefreshForm[redacted]";
        }
    }

    private record GmailSendRequest(String raw) {
        @Override
        public String toString() {
            return "GmailSendRequest[redacted]";
        }
    }
}
