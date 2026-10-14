package com.meetingautomation.notification.slack.admin;

import com.meetingautomation.api.error.FailureContext;

/** Plain-text Incoming Webhook payload; the source context supplies only allowlisted metadata. */
public record SlackAdminIncidentPayload(String text, boolean mrkdwn, boolean unfurl_links) {
    public static SlackAdminIncidentPayload from(FailureContext context) {
        StringBuilder text = new StringBuilder("Incident: ").append(context.incidentType().name());
        append(text, "Session", context.sessionId());
        append(text, "Stage", context.stage());
        append(text, "Trace", context.traceId());
        append(text, "Message", context.safeMessage());
        text.append("\nRetryable: ").append(context.retryable());
        text.append("\nOccurred at: ").append(context.occurredAt());
        return new SlackAdminIncidentPayload(text.toString(), false, false);
    }

    private static void append(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append('\n').append(label).append(": ").append(plain(value));
        }
    }

    private static String plain(String value) {
        return value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll(" +", " ").trim();
    }
}
