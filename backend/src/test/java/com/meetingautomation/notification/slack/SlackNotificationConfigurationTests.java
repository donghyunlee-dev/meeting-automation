package com.meetingautomation.notification.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingautomation.api.health.IntegrationArea;
import com.meetingautomation.api.health.IntegrationHealthContributor;
import com.meetingautomation.api.health.IntegrationHealthStatus;
import com.meetingautomation.notification.NotificationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class SlackNotificationConfigurationTests {
    private org.springframework.boot.test.context.runner.ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(SettingsServiceFixture.class).withBean(
                com.meetingautomation.document.settings.GlobalSettingsStore.class,
                com.meetingautomation.document.settings.InMemoryGlobalSettingsStore::new);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    public static class SettingsServiceFixture {
        @org.springframework.context.annotation.Bean
        SlackNotificationSettings slackNotificationSettings(
                @org.springframework.beans.factory.annotation.Value("${NOTIFICATION_PROVIDER:}") String provider,
                @org.springframework.beans.factory.annotation.Value("${SLACK_MEETING_WEBHOOK_URL:}") String webhookUrl) {
            return new SlackNotificationSettings(provider, webhookUrl);
        }

        @org.springframework.context.annotation.Bean
        SlackWebhookSettingsService slackWebhookSettingsService(
                com.meetingautomation.document.settings.GlobalSettingsStore store,
                @org.springframework.beans.factory.annotation.Value("${NOTIFICATION_PROVIDER:}") String provider,
                @org.springframework.beans.factory.annotation.Value("${SLACK_MEETING_WEBHOOK_URL:}") String webhookUrl) {
            return new SlackWebhookSettingsService(store, provider, webhookUrl);
        }
    }

    @Test
    void selectedSlackRegistersNotificationPortAndConfiguredHealthWithoutProbingWebhook() {
        context().withUserConfiguration(SlackNotificationConfiguration.class)
                .withPropertyValues("NOTIFICATION_PROVIDER=SLACK",
                        "SLACK_MEETING_WEBHOOK_URL=https://hooks.slack.com/services/T/B/secret-token")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationProvider.class);
                    var contributor = context.getBean(IntegrationHealthContributor.class);
                    assertThat(contributor.area()).isEqualTo(IntegrationArea.NOTIFICATION);
                    assertThat(contributor.health()).isEqualTo(new IntegrationHealthStatus(true, false));
                });
    }

    @Test
    void unselectedProviderKeepsSlackHealthUnconfiguredEvenWhenWebhookIsPresent() {
        context().withUserConfiguration(SlackNotificationConfiguration.class)
                .withPropertyValues("NOTIFICATION_PROVIDER=NONE",
                        "SLACK_MEETING_WEBHOOK_URL=https://hooks.slack.com/services/T/B/secret-token")
                .run(context -> {
                    assertThat(context.getBean(IntegrationHealthContributor.class).health())
                            .isEqualTo(new IntegrationHealthStatus(false, false));
                    assertThat(context.getBean(NotificationProvider.class).validateConnection())
                            .isEqualTo(new IntegrationHealthStatus(false, false));
                });
    }
}
