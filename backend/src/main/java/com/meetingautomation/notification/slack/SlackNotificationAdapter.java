package com.meetingautomation.notification.slack;

import com.meetingautomation.api.health.IntegrationHealthStatus;
import com.meetingautomation.notification.DeliveryResult;
import com.meetingautomation.notification.MeetingPublishedCommand;
import com.meetingautomation.notification.NotificationProvider;
import java.net.URI;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Incoming webhook HTTP and response handling is isolated from application services. */
public final class SlackNotificationAdapter implements NotificationProvider {
    private final RestClient client;
    private final Supplier<SlackNotificationSettings> settings;
    private final SlackWebhookSettingsService managedSettings;
    private volatile boolean lastDeliverySucceeded;
    private volatile String lastDeliveryWebhook;

    public SlackNotificationAdapter(RestClient.Builder builder, SlackNotificationSettings settings) {
        this(builder, () -> settings, null);
    }

    public SlackNotificationAdapter(RestClient.Builder builder, SlackWebhookSettingsService settings) {
        this(builder, settings::current, settings);
    }

    private SlackNotificationAdapter(RestClient.Builder builder, Supplier<SlackNotificationSettings> settings,
            SlackWebhookSettingsService managedSettings) {
        this.client = builder.build();
        this.settings = settings;
        this.managedSettings = managedSettings;
    }

    @Override public IntegrationHealthStatus validateConnection() {
        SlackNotificationSettings current = settings.get();
        boolean verified = managedSettings != null && managedSettings.snapshot().connectionVerified();
        boolean recentlyDelivered = lastDeliverySucceeded && current.webhookUrl() != null
                && current.webhookUrl().equals(lastDeliveryWebhook);
        return new IntegrationHealthStatus(current.configured(), current.configured() && (verified || recentlyDelivered));
    }

    @Override public DeliveryResult sendMeetingPublished(MeetingPublishedCommand command) {
        SlackNotificationSettings current = settings.get();
        if (!current.configured() || !valid(command)) {
            lastDeliverySucceeded = false;
            return DeliveryResult.failed(!current.configured());
        }
        SendOutcome outcome = send(current.webhookUrl(), message(command));
        lastDeliverySucceeded = outcome.success();
        lastDeliveryWebhook = current.webhookUrl();
        if (outcome.success()) return DeliveryResult.sent();
        return DeliveryResult.failed(outcome.status() == 403 || outcome.status() == 404 || outcome.status() == 429);
    }

    public boolean sendTestMessage() {
        SlackNotificationSettings current = settings.get();
        if (!current.configured()) return false;
        SendOutcome outcome = send(current.webhookUrl(), "Meeting Automation 연결 테스트 메시지입니다.");
        lastDeliverySucceeded = outcome.success();
        lastDeliveryWebhook = current.webhookUrl();
        if (managedSettings != null) {
            if (outcome.success()) managedSettings.markConnectionVerified(current.webhookUrl());
            else managedSettings.markConnectionUnverified(current.webhookUrl());
        }
        return outcome.success();
    }

    private SendOutcome send(String webhookUrl, String text) {
        String response;
        int status;
        try {
            HttpReply reply = client.post().uri(URI.create(webhookUrl.trim()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SlackPayload(text, false, false))
                    .exchange((request, providerResponse) -> new HttpReply(providerResponse.getStatusCode().value(),
                            providerResponse.getBody().readAllBytes()));
            status = reply.status();
            response = new String(reply.body(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ambiguous) {
            return new SendOutcome(false, 0);
        }
        boolean ok = status == 200 && "ok".equals(response.trim());
        return new SendOutcome(ok, status);
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
    private record SendOutcome(boolean success, int status) { }
}
