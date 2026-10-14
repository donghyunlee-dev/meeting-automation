package com.meetingautomation.notification.slack;

import com.meetingautomation.api.health.IntegrationArea;
import com.meetingautomation.api.health.IntegrationHealthContributor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class SlackNotificationConfiguration {
    @Bean SlackNotificationSettings slackNotificationSettings(
            @Value("${NOTIFICATION_PROVIDER:}") String provider,
            @Value("${SLACK_MEETING_WEBHOOK_URL:}") String webhookUrl) {
        return new SlackNotificationSettings(provider, webhookUrl);
    }

    @Bean SlackNotificationAdapter slackNotificationAdapter(SlackNotificationSettings settings) {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new SlackNotificationAdapter(RestClient.builder().requestFactory(factory), settings);
    }

    @Bean IntegrationHealthContributor notificationHealthContributor(SlackNotificationAdapter adapter) {
        return new IntegrationHealthContributor() {
            @Override public IntegrationArea area() { return IntegrationArea.NOTIFICATION; }
            @Override public com.meetingautomation.api.health.IntegrationHealthStatus health() {
                return adapter.validateConnection();
            }
        };
    }
}
