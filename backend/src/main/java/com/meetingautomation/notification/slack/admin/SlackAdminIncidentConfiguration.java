package com.meetingautomation.notification.slack.admin;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class SlackAdminIncidentConfiguration {
    @Bean
    SlackAdminIncidentSettings slackAdminIncidentSettings(
            @Value("${SLACK_ADMIN_WEBHOOK_URL:}") String webhookUrl) {
        return new SlackAdminIncidentSettings(webhookUrl);
    }

    @Bean
    SlackAdminIncidentNotifier slackAdminIncidentNotifier(SlackAdminIncidentSettings settings) {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new SlackAdminIncidentNotifier(RestClient.builder().requestFactory(factory), settings);
    }
}
