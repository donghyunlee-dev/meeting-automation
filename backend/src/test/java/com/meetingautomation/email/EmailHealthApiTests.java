package com.meetingautomation.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.meetingautomation.api.config.AppConfigController;
import com.meetingautomation.api.health.DocumentHealthContributor;
import com.meetingautomation.api.health.DocumentHealthStatus;
import com.meetingautomation.api.health.IntegrationHealthAggregator;
import com.meetingautomation.api.health.IntegrationHealthContributor;
import com.meetingautomation.api.health.IntegrationHealthController;
import com.meetingautomation.document.settings.GlobalSettingsUseCase;
import com.meetingautomation.email.gmail.GmailEmailAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class EmailHealthApiTests {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(EmailProviderConfiguration.class, GoogleHttpBoundary.class, AppConfigController.class,
                        EmailProviderConfigurationTests.SettingsServiceFixture.class,
                        com.meetingautomation.notification.slack.SlackNotificationConfigurationTests.SettingsServiceFixture.class)
                .withBean(com.meetingautomation.document.settings.GlobalSettingsStore.class,
                        com.meetingautomation.document.settings.InMemoryGlobalSettingsStore::new)
                .withBean(GlobalSettingsUseCase.class, () -> {
                    var settings = mock(GlobalSettingsUseCase.class);
                    when(settings.status()).thenReturn(new GlobalSettingsUseCase.Status(1L, "UNCONFIGURED", null, null, null, false));
                    return settings;
                })
                .withPropertyValues("EMAIL_PROVIDER=GMAIL_API", "EMAIL_OAUTH_CLIENT_ID=private-client-id",
                        "EMAIL_OAUTH_CLIENT_SECRET=private-client-secret", "EMAIL_OAUTH_REFRESH_TOKEN=private-refresh-token",
                        "EMAIL_SENDER_ADDRESS=private-sender@example.test");
    }

    @Test
    void appConfigExposesConfiguredEmailWithoutRefreshingOrSending() {
        context().run(context -> {
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(AppConfigController.class)).build();
            mvc.perform(get("/api/v1/app-config")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email.enabled").value(true))
                    .andExpect(jsonPath("$.data.email.configured").value(true))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("private-"));
            context.getBean(MockRestServiceServer.class).verify();
        });
    }

    @Test
    void api019ReachableReflectsFreshOAuthRefreshAndNeverSendsMail() {
        context().run(context -> {
            var server = context.getBean(MockRestServiceServer.class);
            server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("""
                    {"access_token":"private-access-token","expires_in":3600,"token_type":"Bearer",
                     "scope":"https://www.googleapis.com/auth/gmail.send"}
                    """, MediaType.APPLICATION_JSON));
            server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatusCode.valueOf(400))
                    .body("{\"error\":\"invalid_grant\",\"error_description\":\"private-provider-body\"}"));
            var document = mock(DocumentHealthContributor.class);
            when(document.health()).thenReturn(DocumentHealthStatus.unavailable(null, false));
            var aggregator = new IntegrationHealthAggregator(document,
                    List.of(context.getBean(IntegrationHealthContributor.class)), "");
            var mvc = MockMvcBuilders.standaloneSetup(new IntegrationHealthController(aggregator)).build();
            mvc.perform(get("/api/v1/integrations/health")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email.configured").value(true))
                    .andExpect(jsonPath("$.data.email.reachable").value(true))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("private-"));
            mvc.perform(get("/api/v1/integrations/health")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email.configured").value(true))
                    .andExpect(jsonPath("$.data.email.reachable").value(false))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("private-"));
            server.verify();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 503})
    void failedOAuthRefreshKeepsConfiguredTrueAndReachableFalse(int code) {
        context().run(context -> {
            var server = context.getBean(MockRestServiceServer.class);
            server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatusCode.valueOf(code)).body("private-provider-body"));
            assertThat(context.getBean(IntegrationHealthContributor.class).health())
                    .isEqualTo(new com.meetingautomation.api.health.IntegrationHealthStatus(true, false));
            server.verify();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GoogleHttpBoundary {
        @Bean
        RestClient.Builder gmailHttpBuilder() { return RestClient.builder(); }

        @Bean
        MockRestServiceServer googleHttpServer(RestClient.Builder gmailHttpBuilder) {
            return MockRestServiceServer.bindTo(gmailHttpBuilder).build();
        }

        @Bean
        @Primary
        EmailProvider testGmailProvider(RestClient.Builder gmailHttpBuilder, MockRestServiceServer googleHttpServer,
                EmailSettings settings) {
            return new GmailEmailAdapter(gmailHttpBuilder, settings);
        }
    }
}
