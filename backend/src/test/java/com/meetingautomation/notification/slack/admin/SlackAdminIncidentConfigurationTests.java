package com.meetingautomation.notification.slack.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingautomation.notification.slack.SlackNotificationConfiguration;
import com.meetingautomation.notification.slack.SlackNotificationSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SlackAdminIncidentConfigurationTests {
    private static final String MEETING_URL = "https://hooks.slack.com/services/T-MEETING/B-MEETING/meeting-secret";
    private static final String ADMIN_URL = "https://hooks.slack.com/services/T-ADMIN/B-ADMIN/admin-secret";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SlackNotificationConfiguration.class, SlackAdminIncidentConfiguration.class);

    @Test
    void meetingAndAdminWebhooksAreBoundToIndependentSettings() {
        contextRunner.withPropertyValues(
                "NOTIFICATION_PROVIDER=SLACK",
                "SLACK_MEETING_WEBHOOK_URL=" + MEETING_URL,
                "SLACK_ADMIN_WEBHOOK_URL=" + ADMIN_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SlackNotificationSettings.class).webhookUrl()).isEqualTo(MEETING_URL);
                    assertThat(context.getBean(SlackAdminIncidentSettings.class).webhookUrl()).isEqualTo(ADMIN_URL);
                });
    }

    @Test
    void meetingWebhookDoesNotConfigureAdminNotifier() {
        contextRunner.withPropertyValues("NOTIFICATION_PROVIDER=SLACK", "SLACK_MEETING_WEBHOOK_URL=" + MEETING_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SlackNotificationSettings.class).configured()).isTrue();
                    assertThat(context.getBean(SlackAdminIncidentSettings.class).configured()).isFalse();
                });
    }

    @Test
    void adminWebhookDoesNotConfigureMeetingNotification() {
        contextRunner.withPropertyValues("SLACK_ADMIN_WEBHOOK_URL=" + ADMIN_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SlackAdminIncidentSettings.class).configured()).isTrue();
                    assertThat(context.getBean(SlackNotificationSettings.class).configured()).isFalse();
                });
    }
}
