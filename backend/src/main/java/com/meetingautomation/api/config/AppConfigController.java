package com.meetingautomation.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class AppConfigController {
    private final com.meetingautomation.document.settings.GlobalSettingsUseCase settings;
    private final AppConfigResponse.Company company;
    private final com.meetingautomation.email.EmailSettingsService emailSettings;
    private final com.meetingautomation.notification.slack.SlackWebhookSettingsService slackSettings;
    private final int chunkDurationSeconds;
    private final int maxMeetingDurationMinutes;

    public AppConfigController(
            com.meetingautomation.document.settings.GlobalSettingsUseCase settings,
            @Value("${APP_COMPANY_ID:sfood}") String companyId,
            @Value("${APP_COMPANY_NAME:SFOOD}") String companyName,
            @Value("${APP_TIMEZONE:Asia/Seoul}") String timezone,
            com.meetingautomation.email.EmailSettingsService emailSettings,
            com.meetingautomation.notification.slack.SlackWebhookSettingsService slackSettings,
            @Value("${RECORDING_CHUNK_DURATION_SECONDS:15}") int chunkDurationSeconds,
            @Value("${MAX_MEETING_DURATION_MINUTES:60}") int maxMeetingDurationMinutes) {
        this.settings = settings;
        this.company = new AppConfigResponse.Company(companyId, companyName, timezone);
        this.emailSettings = emailSettings;
        this.slackSettings = slackSettings;
        this.chunkDurationSeconds = chunkDurationSeconds;
        this.maxMeetingDurationMinutes = maxMeetingDurationMinutes;
    }

    @GetMapping("/app-config")
    public AppConfigResponse appConfig() {
        var status = settings.status();
        var active = status.active();
        AppConfigResponse.Document document = new AppConfigResponse.Document(
                active == null ? null : active.provider(), active != null,
                active == null ? 0 : active.connectionVersion(), active == null ? null : active.rootUrl());
        AppConfigResponse.Setup setup = new AppConfigResponse.Setup(status.status(), active == null,
                status.canCreateMeeting(), status.operation() == null ? null : status.operation().operationId());
        boolean emailConfigured = emailSettings.snapshot().configured();
        AppConfigResponse.Email email = new AppConfigResponse.Email(emailConfigured, emailConfigured);
        boolean slackConfigured = slackSettings.snapshot().configured();
        AppConfigResponse.Notification notification = new AppConfigResponse.Notification(
                slackConfigured ? "SLACK" : null, slackConfigured);
        AppConfigResponse.Recording recording = new AppConfigResponse.Recording(
                chunkDurationSeconds, maxMeetingDurationMinutes);
        return new AppConfigResponse(new AppConfigResponse.Data(company, document, setup, email, notification, recording));
    }

}
