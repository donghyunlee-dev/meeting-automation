package com.meetingautomation.email.gmail;

import com.meetingautomation.email.EmailProvider;
import com.meetingautomation.email.EmailSettings;
import com.meetingautomation.email.MeetingEmailCommand;
import com.meetingautomation.email.RecipientDeliveryResult;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
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
    private final EmailSettings settings;
    private final Clock clock;
    // Access tokens are held only inside this adapter instance, never persisted or shared externally.
    private String accessToken;
    private Instant expiresAt = Instant.MIN;

    public GmailEmailAdapter(RestClient.Builder builder, EmailSettings settings) {
        this(builder, settings, Clock.systemUTC());
    }

    public GmailEmailAdapter(RestClient.Builder builder, EmailSettings settings, Clock clock) {
        this.client = builder.build();
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean validateConnection() {
        return token(true) != null;
    }

    @Override
    public List<RecipientDeliveryResult> sendMeetingEmail(MeetingEmailCommand command) {
        if (command.recipients().isEmpty()) return List.of();
        if (!settings.configured() || !validCommand(command)) return failures(command, true);
        if (command.recipients().stream().noneMatch(recipient -> EmailSettings.validAddress(recipient.email()))) {
            return failures(command, true);
        }
        String token = token(false);
        if (token == null) return failures(command, true);
        List<RecipientDeliveryResult> results = new ArrayList<>();
        for (var recipient : command.recipients()) {
            if (!EmailSettings.validAddress(recipient.email())) {
                results.add(RecipientDeliveryResult.failed(recipient.participantId(), true));
                continue;
            }
            results.add(send(command, recipient, token));
        }
        return List.copyOf(results);
    }

    private static List<RecipientDeliveryResult> failures(MeetingEmailCommand command, boolean retryable) {
        return command.recipients().stream()
                .map(recipient -> RecipientDeliveryResult.failed(recipient.participantId(), retryable)).toList();
    }

    private synchronized String token(boolean forceRefresh) {
        if (!settings.configured()) return null;
        if (!forceRefresh && accessToken != null && clock.instant().isBefore(expiresAt)) return accessToken;
        accessToken = null;
        expiresAt = Instant.MIN;
        try {
            var form = new OAuthRefreshForm();
            form.add("client_id", settings.clientId().trim());
            form.add("client_secret", settings.clientSecret().trim());
            form.add("refresh_token", settings.refreshToken().trim());
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
            String token) {
        final String raw;
        try {
            raw = mime(command, recipient.email().trim());
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

    private String mime(MeetingEmailCommand command, String recipient) throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(settings.senderAddress().trim()));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
        message.setSubject(command.subject(), StandardCharsets.UTF_8.name());
        message.setText(command.textBody() + "\n\n" + command.documentUrl(), StandardCharsets.UTF_8.name());
        message.saveChanges();
        var bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
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
