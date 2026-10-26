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
            @Value("${transcription.model:}") String model,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${transcription.max-audio-bytes:26214400}") long maxAudioBytes,
            @Value("${transcription.connect-timeout-seconds:10}") int connectTimeoutSeconds,
            @Value("${transcription.timeout-seconds:1800}") int timeoutSeconds) {
        if (maxAudioBytes < 1 || connectTimeoutSeconds < 1 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("Transcription provider configuration is invalid.");
        }
        return new TranscriptionSettings(model, apiKey, maxAudioBytes, connectTimeoutSeconds, timeoutSeconds);
    }

    @Bean
    TranscriptionProvider transcriptionProvider(TranscriptionSettings settings) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(settings.timeoutSeconds()));
        RestClient.Builder clientBuilder = RestClient.builder().requestFactory(requestFactory);
        return new SegmentingTranscriptionProvider(new OpenAiTranscriptionAdapter(clientBuilder, settings),
                new FfmpegAudioSegmenter("ffmpeg"), settings.maxAudioBytes());
    }
}
