package com.meetingautomation.minutes.openai;

import com.meetingautomation.minutes.MinutesGenerationProvider;
import com.meetingautomation.minutes.MinutesGenerationSettings;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class MinutesGenerationProviderConfiguration {
    @Bean
    MinutesGenerationSettings minutesGenerationSettings(
            @Value("${MINUTES_MODEL:}") String model,
            @Value("${OPENAI_API_KEY:}") String apiKey,
            @Value("${MINUTES_CONNECT_TIMEOUT_SECONDS:10}") int connectTimeoutSeconds,
            @Value("${MINUTES_TIMEOUT_SECONDS:600}") int timeoutSeconds) {
        if (connectTimeoutSeconds < 1 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Minutes provider configuration is invalid.");
        }
        return new MinutesGenerationSettings(model, apiKey, connectTimeoutSeconds, timeoutSeconds);
    }

    @Bean
    MinutesGenerationProvider minutesGenerationProvider(MinutesGenerationSettings settings) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(settings.timeoutSeconds()));
        return new OpenAiMinutesGenerationAdapter(RestClient.builder().requestFactory(requestFactory), settings);
    }
}
