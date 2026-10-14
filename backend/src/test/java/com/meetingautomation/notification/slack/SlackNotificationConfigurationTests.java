package com.meetingautomation.notification.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingautomation.api.health.IntegrationArea;
import com.meetingautomation.api.health.IntegrationHealthContributor;
import com.meetingautomation.api.health.IntegrationHealthStatus;
import com.meetingautomation.notification.NotificationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SlackNotificationConfigurationTests {
    @Test
    void selectedSlackRegistersNotificationPortAndConfiguredHealthWithoutProbingWebhook() {
        new ApplicationContextRunner().withUserConfiguration(SlackNotificationConfiguration.class)
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
        new ApplicationContextRunner().withUserConfiguration(SlackNotificationConfiguration.class)
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
