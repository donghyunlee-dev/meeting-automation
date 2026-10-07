package com.meetingautomation.api.config;

import com.meetingautomation.document.DocumentProviderResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class AppConfigController {
    private final DocumentProviderResolver documentProviderResolver;
    private final AppConfigResponse.Company company;
    private final String emailProvider;
    private final String emailClientId;
    private final String emailClientSecret;
    private final String emailRefreshToken;
    private final String emailSenderAddress;
    private final String notificationProvider;
    private final int chunkDurationSeconds;
    private final int maxMeetingDurationMinutes;

    public AppConfigController(
            DocumentProviderResolver documentProviderResolver,
            @Value("${APP_COMPANY_ID:sfood}") String companyId,
            @Value("${APP_COMPANY_NAME:SFOOD}") String companyName,
            @Value("${APP_TIMEZONE:Asia/Seoul}") String timezone,
            @Value("${EMAIL_PROVIDER:}") String emailProvider,
            @Value("${EMAIL_OAUTH_CLIENT_ID:}") String emailClientId,
            @Value("${EMAIL_OAUTH_CLIENT_SECRET:}") String emailClientSecret,
            @Value("${EMAIL_OAUTH_REFRESH_TOKEN:}") String emailRefreshToken,
            @Value("${EMAIL_SENDER_ADDRESS:}") String emailSenderAddress,
            @Value("${NOTIFICATION_PROVIDER:}") String notificationProvider,
            @Value("${RECORDING_CHUNK_DURATION_SECONDS:15}") int chunkDurationSeconds,
            @Value("${MAX_MEETING_DURATION_MINUTES:60}") int maxMeetingDurationMinutes) {
        this.documentProviderResolver = documentProviderResolver;
        this.company = new AppConfigResponse.Company(companyId, companyName, timezone);
        this.emailProvider = emailProvider;
        this.emailClientId = emailClientId;
        this.emailClientSecret = emailClientSecret;
        this.emailRefreshToken = emailRefreshToken;
        this.emailSenderAddress = emailSenderAddress;
        this.notificationProvider = normalizedOrNull(notificationProvider);
        this.chunkDurationSeconds = chunkDurationSeconds;
        this.maxMeetingDurationMinutes = maxMeetingDurationMinutes;
    }

    @GetMapping("/app-config")
    public AppConfigResponse appConfig() {
        documentProviderResolver.validateSelection();
        AppConfigResponse.Document document = new AppConfigResponse.Document(
                documentProviderResolver.providerId(), documentProviderResolver.configured());
        AppConfigResponse.Email email = new AppConfigResponse.Email(
                !isBlank(emailProvider), isEmailConfigured());
        AppConfigResponse.Notification notification = new AppConfigResponse.Notification(
                notificationProvider, notificationProvider != null);
        AppConfigResponse.Recording recording = new AppConfigResponse.Recording(
                chunkDurationSeconds, maxMeetingDurationMinutes);
        return new AppConfigResponse(new AppConfigResponse.Data(company, document, email, notification, recording));
    }

    private boolean isEmailConfigured() {
        return "GMAIL_API".equals(emailProvider)
                && !isBlank(emailClientId)
                && !isBlank(emailClientSecret)
                && !isBlank(emailRefreshToken)
                && isValidEmail(emailSenderAddress);
    }

    private static boolean isValidEmail(String value) {
        return value != null && value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private static String normalizedOrNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
