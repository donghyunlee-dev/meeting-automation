package com.meetingautomation.notification.slack;

import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Stores Slack webhook credentials alongside the encrypted document connection snapshot. */
@Service
public final class SlackWebhookSettingsService {
    private final GlobalSettingsStore settingsStore;
    private final SlackNotificationSettings environmentSettings;
    private final Clock clock;

    @Autowired
    public SlackWebhookSettingsService(GlobalSettingsStore settingsStore,
            @Value("${NOTIFICATION_PROVIDER:}") String provider,
            @Value("${SLACK_MEETING_WEBHOOK_URL:}") String webhookUrl) {
        this(settingsStore, new SlackNotificationSettings(provider == null ? "" : provider.trim(), webhookUrl), Clock.systemUTC());
    }

    SlackWebhookSettingsService(GlobalSettingsStore settingsStore, SlackNotificationSettings environmentSettings, Clock clock) {
        this.settingsStore = settingsStore;
        this.environmentSettings = environmentSettings;
        this.clock = clock;
    }

    public SlackNotificationSettings current() {
        String saved = settingsStore.read().slackNotification().webhookUrl();
        return saved == null || saved.isBlank() ? environmentSettings : new SlackNotificationSettings("SLACK", saved);
    }

    public Snapshot snapshot() {
        GlobalDocumentSettings.SlackNotification saved = settingsStore.read().slackNotification();
        SlackNotificationSettings current = saved.webhookUrl() == null || saved.webhookUrl().isBlank()
                ? environmentSettings : new SlackNotificationSettings("SLACK", saved.webhookUrl());
        boolean verified = saved.webhookUrl() != null && !saved.webhookUrl().isBlank() && saved.connectionVerified();
        return new Snapshot(current.configured(), verified, current.configured() ? "••••••••" : null,
                saved.verifiedAt() == null ? null : saved.verifiedAt().toString());
    }

    public Snapshot save(String webhookUrl) {
        String normalized = webhookUrl == null ? "" : webhookUrl.trim();
        if (!SlackNotificationSettings.validWebhookUrl(normalized)) throw new InvalidSlackWebhookException();
        settingsStore.transaction(current -> {
            GlobalDocumentSettings.SlackNotification nextSlack = new GlobalDocumentSettings.SlackNotification(normalized, false, null);
            GlobalDocumentSettings next = withSlack(current, nextSlack);
            return new GlobalSettingsStore.Change<>(next, null);
        });
        return snapshot();
    }

    public Snapshot delete() {
        settingsStore.transaction(current -> {
            GlobalDocumentSettings next = withSlack(current, GlobalDocumentSettings.SlackNotification.empty());
            return new GlobalSettingsStore.Change<>(next, null);
        });
        return snapshot();
    }

    public void markConnectionVerified(String webhookUrl) {
        String normalized = webhookUrl == null ? "" : webhookUrl.trim();
        settingsStore.transaction(current -> {
            GlobalDocumentSettings.SlackNotification saved = current.slackNotification();
            if (saved.webhookUrl() == null || !saved.webhookUrl().equals(normalized)) {
                return new GlobalSettingsStore.Change<>(current, null);
            }
            GlobalDocumentSettings next = withSlack(current,
                    new GlobalDocumentSettings.SlackNotification(normalized, true, Instant.now(clock)));
            return new GlobalSettingsStore.Change<>(next, null);
        });
    }

    public void markConnectionUnverified(String webhookUrl) {
        String normalized = webhookUrl == null ? "" : webhookUrl.trim();
        settingsStore.transaction(current -> {
            GlobalDocumentSettings.SlackNotification saved = current.slackNotification();
            if (saved.webhookUrl() == null || !saved.webhookUrl().equals(normalized) || !saved.connectionVerified()) {
                return new GlobalSettingsStore.Change<>(current, null);
            }
            GlobalDocumentSettings next = withSlack(current,
                    new GlobalDocumentSettings.SlackNotification(normalized, false, null));
            return new GlobalSettingsStore.Change<>(next, null);
        });
    }

    private static GlobalDocumentSettings withSlack(GlobalDocumentSettings current,
            GlobalDocumentSettings.SlackNotification slack) {
        return new GlobalDocumentSettings(current.schemaVersion(), current.version(), current.active(), current.draft(),
                current.operation(), current.idempotencyRecords(), current.operationHistory(), slack, current.emailNotification());
    }

    public record Snapshot(boolean configured, boolean connectionVerified, String maskedWebhookUrl, String verifiedAt) { }
    public static final class InvalidSlackWebhookException extends RuntimeException { }
}
