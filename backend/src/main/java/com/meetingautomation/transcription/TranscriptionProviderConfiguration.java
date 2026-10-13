package com.meetingautomation.transcription;

import com.meetingautomation.transcription.openai.OpenAiTranscriptionAdapter;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class TranscriptionProviderConfiguration {
    @Bean
    TranscriptionSettings transcriptionSettings(
            @Value("${TRANSCRIPTION_MODEL:}") String model,
            @Value("${OPENAI_API_KEY:}") String apiKey,
            @Value("${TRANSCRIPTION_MAX_AUDIO_BYTES:26214400}") long maxAudioBytes) {
        if (maxAudioBytes < 1) throw new IllegalArgumentException("Transcription provider configuration is invalid.");
        return new TranscriptionSettings(model, apiKey, maxAudioBytes);
    }

    @Bean
    TranscriptionProvider transcriptionProvider(
            TranscriptionSettings settings,
            @Value("${TRANSCRIPTION_TIMEOUT_SECONDS:600}") int timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("Transcription timeout configuration is invalid.");
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient.Builder clientBuilder = RestClient.builder().requestFactory(requestFactory);
        return new OpenAiTranscriptionAdapter(clientBuilder, settings);
    }
}
