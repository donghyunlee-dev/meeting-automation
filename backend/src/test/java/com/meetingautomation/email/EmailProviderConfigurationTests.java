package com.meetingautomation.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.meetingautomation.api.config.AppConfigController;
import com.meetingautomation.api.health.IntegrationArea;
import com.meetingautomation.api.health.IntegrationHealthContributor;
import com.meetingautomation.api.health.IntegrationHealthStatus;
import com.meetingautomation.document.settings.GlobalSettingsUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmailProviderConfigurationTests {
    private org.springframework.boot.test.context.runner.ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(SettingsServiceFixture.class,
                com.meetingautomation.notification.slack.SlackNotificationConfigurationTests.SettingsServiceFixture.class).withBean(
                com.meetingautomation.document.settings.GlobalSettingsStore.class,
                com.meetingautomation.document.settings.InMemoryGlobalSettingsStore::new);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class SettingsServiceFixture {
        @org.springframework.context.annotation.Bean
        EmailSettingsService emailSettingsService(
                com.meetingautomation.document.settings.GlobalSettingsStore store, EmailSettings settings) {
            return new EmailSettingsService(store, settings);
        }
    }

    @Test
    void selectedGmailWithoutCredentialsRegistersSafeUnconfiguredEmailHealth() {
        context().withUserConfiguration(EmailProviderConfiguration.class)
                .withPropertyValues("EMAIL_PROVIDER=GMAIL_API")
                .run(context -> {
                    var contributors = context.getBeansOfType(IntegrationHealthContributor.class).values();
                    assertThat(contributors).hasSize(1);
                    var contributor = contributors.iterator().next();
                    assertThat(contributor.area()).isEqualTo(IntegrationArea.EMAIL);
                    assertThat(contributor.health()).isEqualTo(new IntegrationHealthStatus(false, false));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMAIL_PROVIDER", "EMAIL_OAUTH_CLIENT_ID", "EMAIL_OAUTH_CLIENT_SECRET",
            "EMAIL_OAUTH_REFRESH_TOKEN", "EMAIL_SENDER_ADDRESS"})
    void anyMissingRequiredSettingDisablesEmailHealthWithoutGoogleCalls(String missing) {
        var properties = new java.util.LinkedHashMap<String, String>();
        properties.put("EMAIL_PROVIDER", "GMAIL_API");
        properties.put("EMAIL_OAUTH_CLIENT_ID", "test-client");
        properties.put("EMAIL_OAUTH_CLIENT_SECRET", "test-secret");
        properties.put("EMAIL_OAUTH_REFRESH_TOKEN", "test-refresh");
        properties.put("EMAIL_SENDER_ADDRESS", "sender@example.test");
        properties.put(missing, " ");
        context().withUserConfiguration(EmailProviderConfiguration.class)
                .withPropertyValues(properties.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new))
                .run(context -> {
                    assertThat(context.getBean(EmailSettings.class).configured()).isFalse();
                    assertThat(context.getBean(IntegrationHealthContributor.class).health())
                            .isEqualTo(new IntegrationHealthStatus(false, false));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SMTP", "UNSUPPORTED"})
    void unselectedOrUnsupportedProviderCannotSendEvenWithOAuthSettings(String provider) {
        context().withUserConfiguration(EmailProviderConfiguration.class)
                .withPropertyValues("EMAIL_PROVIDER=" + provider, "EMAIL_OAUTH_CLIENT_ID=test-client",
                        "EMAIL_OAUTH_CLIENT_SECRET=test-secret", "EMAIL_OAUTH_REFRESH_TOKEN=test-refresh",
                        "EMAIL_SENDER_ADDRESS=sender@example.test")
                .run(context -> {
                    var email = context.getBean(EmailProvider.class);
                    assertThat(email.validateConnection()).isFalse();
                    assertThat(email.sendMeetingEmail(new MeetingEmailCommand("Subject", "Body", "https://docs.example.test/saved",
                            java.util.List.of(new MeetingEmailCommand.Recipient("pt_1", "first@example.test")))))
                            .containsExactly(RecipientDeliveryResult.failed("pt_1", true));
                });
    }

    @Test
    void appConfigRejectsSenderListsWithSameConfigurationRulesAsProviderHealth() {
        var settings = mock(GlobalSettingsUseCase.class);
        when(settings.status()).thenReturn(new GlobalSettingsUseCase.Status(1L, "UNCONFIGURED", null, null, null, false));
        context().withUserConfiguration(EmailProviderConfiguration.class, AppConfigController.class)
                .withBean(GlobalSettingsUseCase.class, () -> settings)
                .withPropertyValues("EMAIL_PROVIDER=GMAIL_API", "EMAIL_OAUTH_CLIENT_ID=test-client",
                        "EMAIL_OAUTH_CLIENT_SECRET=test-secret", "EMAIL_OAUTH_REFRESH_TOKEN=test-refresh",
                        "EMAIL_SENDER_ADDRESS=sender@example.test,hidden")
                .run(context -> {
                    assertThat(context.getBean(AppConfigController.class).appConfig().data().email().configured()).isFalse();
                    assertThat(context.getBean(IntegrationHealthContributor.class).health())
                            .isEqualTo(new IntegrationHealthStatus(false, false));
                });
    }

}
