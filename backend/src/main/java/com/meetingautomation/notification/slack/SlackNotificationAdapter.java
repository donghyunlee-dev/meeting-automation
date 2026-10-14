package com.meetingautomation.notification.slack;

import com.meetingautomation.api.health.IntegrationHealthStatus;
import com.meetingautomation.notification.DeliveryResult;
import com.meetingautomation.notification.MeetingPublishedCommand;
import com.meetingautomation.notification.NotificationProvider;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Incoming webhook HTTP and response handling is isolated from application services. */
public final class SlackNotificationAdapter implements NotificationProvider {
    private final RestClient client;
    private final SlackNotificationSettings settings;
    private volatile boolean lastDeliverySucceeded;

    public SlackNotificationAdapter(RestClient.Builder builder, SlackNotificationSettings settings) {
        this.client = builder.build();
        this.settings = settings;
    }

    @Override public IntegrationHealthStatus validateConnection() {
        return new IntegrationHealthStatus(settings.configured(), settings.configured() && lastDeliverySucceeded);
    }

    @Override public DeliveryResult sendMeetingPublished(MeetingPublishedCommand command) {
        if (!settings.configured() || !valid(command)) {
            lastDeliverySucceeded = false;
            return DeliveryResult.failed(!settings.configured());
        }
        String response;
        int status;
        try {
            HttpReply reply = client.post().uri(URI.create(settings.webhookUrl().trim()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SlackPayload(message(command), false, false))
                    .exchange((request, providerResponse) -> new HttpReply(providerResponse.getStatusCode().value(),
                            providerResponse.getBody().readAllBytes()));
            status = reply.status();
            response = new String(reply.body(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ambiguous) {
            lastDeliverySucceeded = false;
            return DeliveryResult.failed(false);
        }
        boolean ok = status == 200 && "ok".equals(response.trim());
        lastDeliverySucceeded = ok;
        if (ok) return DeliveryResult.sent();
        boolean retryable = status == 403 || status == 404 || status == 429;
        return DeliveryResult.failed(retryable);
    }

    private static boolean valid(MeetingPublishedCommand command) {
        if (command == null || command.title() == null || command.title().isBlank() || command.meetingAt() == null
                || command.documentUrl() == null) return false;
        try {
            URI uri = URI.create(command.documentUrl());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static String message(MeetingPublishedCommand command) {
        return "Meeting published: " + plain(command.title()) + "\nMeeting date: " + command.meetingAt()
                + "\nDocument: " + command.documentUrl();
    }

    private static String plain(String input) {
        return input.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll(" +", " ").trim();
    }

    private record SlackPayload(String text, boolean mrkdwn, boolean unfurl_links) { }
    private record HttpReply(int status, byte[] body) { }
}
