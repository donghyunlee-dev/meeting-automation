package com.meetingautomation.minutes.openai;

import com.meetingautomation.minutes.MinutesGenerationProvider;
import com.meetingautomation.minutes.MinutesGenerationSettings;
import com.meetingautomation.minutes.MinutesDocumentWriter;
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
            @Value("${minutes.model:gpt-6-luna}") String model,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${minutes.connect-timeout-seconds:10}") int connectTimeoutSeconds,
            @Value("${minutes.timeout-seconds:600}") int timeoutSeconds) {
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

    @Bean
    MinutesDocumentWriter minutesDocumentWriter(MinutesGenerationSettings settings) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(settings.timeoutSeconds()));
        return new OpenAiMinutesDocumentWriter(RestClient.builder().requestFactory(requestFactory), settings);
    }
}
