package com.meetingautomation.api.health;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class IntegrationHealthAggregator {
    private final DocumentHealthContributor documentContributor;
    private final List<IntegrationHealthContributor> contributors;
    private final java.util.function.Supplier<String> notificationProvider;

    @Autowired
    public IntegrationHealthAggregator(
            DocumentHealthContributor documentContributor,
            List<IntegrationHealthContributor> contributors,
            com.meetingautomation.notification.slack.SlackWebhookSettingsService slackSettings) {
        this.documentContributor = documentContributor;
        this.contributors = List.copyOf(contributors);
        this.notificationProvider = slackSettings::selectedProvider;
    }

    public IntegrationHealthAggregator(DocumentHealthContributor documentContributor,
            List<IntegrationHealthContributor> contributors, String notificationProvider) {
        this.documentContributor = documentContributor;
        this.contributors = List.copyOf(contributors);
        this.notificationProvider = () -> normalizedOrNull(notificationProvider);
    }

    public IntegrationHealthResponse health() {
        Map<IntegrationArea, IntegrationHealthStatus> statuses = emptyStatuses();
        for (IntegrationHealthContributor contributor : contributors) {
            try {
                IntegrationArea area = contributor.area();
                IntegrationHealthStatus status = contributor.health();
                if (area != null && status != null) {
                    statuses.put(area, status);
                }
            } catch (RuntimeException unavailableContributor) {
                // One optional integration must not prevent the other statuses from being returned.
            }
        }

        DocumentHealthStatus documentStatus;
        try {
            documentStatus = documentContributor.health();
        } catch (RuntimeException unavailableDocumentProvider) {
            documentStatus = DocumentHealthStatus.unavailable(null, false);
        }

        IntegrationHealthStatus notificationStatus = statuses.get(IntegrationArea.NOTIFICATION);
        return new IntegrationHealthResponse(new IntegrationHealthResponse.Data(
                documentStatus,
                statuses.get(IntegrationArea.EMAIL),
                new IntegrationHealthResponse.NotificationHealthStatus(
                        notificationProvider.get(), notificationStatus.configured(), notificationStatus.reachable()),
                statuses.get(IntegrationArea.AI)));
    }

    private static Map<IntegrationArea, IntegrationHealthStatus> emptyStatuses() {
        Map<IntegrationArea, IntegrationHealthStatus> statuses = new EnumMap<>(IntegrationArea.class);
        for (IntegrationArea area : IntegrationArea.values()) {
            statuses.put(area, IntegrationHealthStatus.unavailable());
        }
        return statuses;
    }

    private static String normalizedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
