package com.meetingautomation.notification.slack.admin;

import com.meetingautomation.api.error.FailureContext;
import com.meetingautomation.notification.AdminIncidentNotifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** One-shot, best-effort Slack Admin Incoming Webhook adapter. It never logs URL or response content. */
public final class SlackAdminIncidentNotifier implements AdminIncidentNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(SlackAdminIncidentNotifier.class);
    private final RestClient client;
    private final SlackAdminIncidentSettings settings;

    public SlackAdminIncidentNotifier(RestClient.Builder builder, SlackAdminIncidentSettings settings) {
        this.client = builder.build();
        this.settings = settings;
    }

    @Override
    public void notifyIncident(FailureContext context) {
        if (context == null || !settings.configured()) return;
        HttpReply reply;
        try {
            reply = client.post().uri(URI.create(settings.webhookUrl().trim()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(SlackAdminIncidentPayload.from(context))
                    .exchange((request, response) -> new HttpReply(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            // Do not expose exception text or recursively report this best-effort notification failure.
            logFailure(context, "TRANSPORT_ERROR", null);
            return;
        }
        if (reply.status() != 200) logFailure(context, "HTTP_STATUS", reply.status());
        else if (!"ok".equals(reply.body().trim())) logFailure(context, "INVALID_RESPONSE", null);
    }

    private static void logFailure(FailureContext context, String reason, Integer httpStatus) {
        var event = LOG.atWarn()
                .addKeyValue("event", "admin_incident_notification_failed")
                .addKeyValue("failureReason", reason)
                .addKeyValue("incidentType", context.incidentType().name())
                .addKeyValue("traceId", context.traceId())
                .addKeyValue("retryable", context.retryable())
                .addKeyValue("occurredAt", context.occurredAt().toString());
        if (context.sessionId() != null) event.addKeyValue("sessionId", context.sessionId());
        if (context.stage() != null) event.addKeyValue("stage", context.stage());
        if (httpStatus != null) event.addKeyValue("httpStatus", httpStatus);
        event.log("Admin incident notification failed.");
    }

    private record HttpReply(int status, String body) { }
}
