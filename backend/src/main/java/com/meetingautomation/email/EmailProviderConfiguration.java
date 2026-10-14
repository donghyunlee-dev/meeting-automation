package com.meetingautomation.email;

import com.meetingautomation.api.health.IntegrationArea;
import com.meetingautomation.api.health.IntegrationHealthContributor;
import com.meetingautomation.api.health.IntegrationHealthStatus;
import com.meetingautomation.email.gmail.GmailEmailAdapter;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class EmailProviderConfiguration {
    @Bean
    EmailSettings emailSettings(@Value("${EMAIL_PROVIDER:}") String provider,
            @Value("${EMAIL_OAUTH_CLIENT_ID:}") String clientId,
            @Value("${EMAIL_OAUTH_CLIENT_SECRET:}") String clientSecret,
            @Value("${EMAIL_OAUTH_REFRESH_TOKEN:}") String refreshToken,
            @Value("${EMAIL_SENDER_ADDRESS:}") String senderAddress) {
        return new EmailSettings(provider, clientId, clientSecret, refreshToken, senderAddress);
    }

    @Bean
    EmailProvider emailProvider(EmailSettings settings) {
        var httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new GmailEmailAdapter(RestClient.builder().requestFactory(factory), settings);
    }

    @Bean
    IntegrationHealthContributor emailHealthContributor(EmailSettings settings, EmailProvider provider) {
        return new IntegrationHealthContributor() {
            @Override
            public IntegrationArea area() {
                return IntegrationArea.EMAIL;
            }

            @Override
            public IntegrationHealthStatus health() {
                return new IntegrationHealthStatus(settings.configured(), provider.validateConnection());
            }
        };
    }
}
