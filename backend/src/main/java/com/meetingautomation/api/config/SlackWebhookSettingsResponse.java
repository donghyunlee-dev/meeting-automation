package com.meetingautomation.api.config;

import com.meetingautomation.notification.slack.SlackWebhookSettingsService;

public record SlackWebhookSettingsResponse(Data data) {
    public static SlackWebhookSettingsResponse from(SlackWebhookSettingsService.Snapshot snapshot) {
        return new SlackWebhookSettingsResponse(new Data(snapshot.configured(), snapshot.connectionVerified(),
                snapshot.maskedWebhookUrl(), snapshot.verifiedAt()));
    }

    public record Data(boolean configured, boolean connectionVerified, String maskedWebhookUrl, String verifiedAt) { }
}
