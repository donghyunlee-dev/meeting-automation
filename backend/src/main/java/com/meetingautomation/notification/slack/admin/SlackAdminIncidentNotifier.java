package com.meetingautomation.notification.slack.admin;

import com.meetingautomation.api.error.FailureContext;
import com.meetingautomation.notification.AdminIncidentNotifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** One-shot, best-effort Slack Admin Incoming Webhook adapter. It never logs URL or response content. */
public final class SlackAdminIncidentNotifier implements AdminIncidentNotifier {
    private final RestClient client;
    private final SlackAdminIncidentSettings settings;

    public SlackAdminIncidentNotifier(RestClient.Builder builder, SlackAdminIncidentSettings settings) {
        this.client = builder.build();
        this.settings = settings;
    }

    @Override
    public void notifyIncident(FailureContext context) {
        if (context == null || !settings.configured()) return;
        try {
            HttpReply reply = client.post().uri(URI.create(settings.webhookUrl().trim()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(SlackAdminIncidentPayload.from(context))
                    .exchange((request, response) -> new HttpReply(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            // The result is intentionally not logged or surfaced to the original business operation.
            if (reply.status() != 200 || !"ok".equals(reply.body().trim())) return;
        } catch (Exception ignored) {
            // No retry: request/response ambiguity must not create duplicate or recursive incidents.
        }
    }

    private record HttpReply(int status, String body) { }
}
