package com.meetingautomation.email;

import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Stores Gmail OAuth credentials alongside the encrypted global settings snapshot. */
@Service
public final class EmailSettingsService {
    private final GlobalSettingsStore settingsStore;
    private final EmailSettings environmentSettings;
    private final Clock clock;

    @Autowired
    public EmailSettingsService(GlobalSettingsStore settingsStore, EmailSettings environmentSettings) {
        this(settingsStore, environmentSettings, Clock.systemUTC());
    }

    EmailSettingsService(GlobalSettingsStore settingsStore, EmailSettings environmentSettings, Clock clock) {
        this.settingsStore = settingsStore;
        this.environmentSettings = environmentSettings;
        this.clock = clock;
    }

    public EmailSettings current() {
        GlobalDocumentSettings.EmailNotification saved = settingsStore.read().emailNotification();
        if (complete(saved)) return asSettings(saved);
        return environmentSettings;
    }

    public Snapshot snapshot() {
        GlobalDocumentSettings.EmailNotification saved = settingsStore.read().emailNotification();
        EmailSettings effective = complete(saved) ? asSettings(saved) : environmentSettings;
        boolean verified = complete(saved) && saved.connectionVerified();
        return new Snapshot(effective.configured(), verified,
                effective.configured() ? "••••••••" : null,
                effective.configured() ? "••••••••" : null,
                effective.configured() ? "••••••••" : null,
                effective.configured() ? effective.senderAddress() : null,
                saved.verifiedAt() == null ? null : saved.verifiedAt().toString());
    }

    public Snapshot save(String clientId, String clientSecret, String refreshToken, String senderAddress) {
        String normalizedClientId = clean(clientId);
        String normalizedClientSecret = clean(clientSecret);
        String normalizedRefreshToken = clean(refreshToken);
        String normalizedSenderAddress = clean(senderAddress);
        EmailSettings candidate = new EmailSettings("GMAIL_API", normalizedClientId, normalizedClientSecret,
                normalizedRefreshToken, normalizedSenderAddress);
        if (!candidate.configured()) throw new InvalidEmailSettingsException();
        settingsStore.transaction(current -> {
            GlobalDocumentSettings.EmailNotification nextEmail = new GlobalDocumentSettings.EmailNotification(
                    normalizedClientId, normalizedClientSecret, normalizedRefreshToken, normalizedSenderAddress, false, null);
            return new GlobalSettingsStore.Change<>(withEmail(current, nextEmail), null);
        });
        return snapshot();
    }

    public Snapshot delete() {
        settingsStore.transaction(current -> new GlobalSettingsStore.Change<>(withEmail(current,
                GlobalDocumentSettings.EmailNotification.empty()), null));
        return snapshot();
    }

    public void markConnectionVerified(boolean verified) {
        settingsStore.transaction(current -> {
            GlobalDocumentSettings.EmailNotification saved = current.emailNotification();
            if (!complete(saved)) return new GlobalSettingsStore.Change<>(current, null);
            var nextEmail = new GlobalDocumentSettings.EmailNotification(saved.clientId(), saved.clientSecret(),
                    saved.refreshToken(), saved.senderAddress(), verified, verified ? Instant.now(clock) : null);
            return new GlobalSettingsStore.Change<>(withEmail(current, nextEmail), null);
        });
    }

    private static boolean complete(GlobalDocumentSettings.EmailNotification saved) {
        return saved.clientId() != null && !saved.clientId().isBlank()
                && saved.clientSecret() != null && !saved.clientSecret().isBlank()
                && saved.refreshToken() != null && !saved.refreshToken().isBlank()
                && EmailSettings.validAddress(saved.senderAddress());
    }

    private static EmailSettings asSettings(GlobalDocumentSettings.EmailNotification saved) {
        return new EmailSettings("GMAIL_API", saved.clientId(), saved.clientSecret(), saved.refreshToken(), saved.senderAddress());
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static GlobalDocumentSettings withEmail(GlobalDocumentSettings current,
            GlobalDocumentSettings.EmailNotification email) {
        return new GlobalDocumentSettings(current.schemaVersion(), current.version(), current.active(), current.draft(),
                current.operation(), current.idempotencyRecords(), current.operationHistory(), current.slackNotification(), email);
    }

    public record Snapshot(boolean configured, boolean connectionVerified, String maskedClientId,
            String maskedClientSecret, String maskedRefreshToken, String senderAddress, String verifiedAt) { }

    public static final class InvalidEmailSettingsException extends RuntimeException { }
}
