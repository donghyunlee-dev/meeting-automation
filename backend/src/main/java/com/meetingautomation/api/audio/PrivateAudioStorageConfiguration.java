package com.meetingautomation.api.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Creates private audio storage when the Vercel control Function is configured. */
@Configuration
@EnableScheduling
public class PrivateAudioStorageConfiguration {
    @Bean
    ObjectMapper audioStorageObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnProperty(name = "VERCEL_BLOB_CONTROL_URL")
    PrivateAudioStoragePort vercelBlobPrivateAudioStoragePort(ObjectMapper json,
            @Value("${VERCEL_BLOB_CONTROL_URL}") String controlUrl,
            @Value("${RENDER_AUDIO_STORAGE_SECRET}") String secret) {
        return new VercelBlobPrivateAudioStorageAdapter(HttpClient.newHttpClient(), json, URI.create(controlUrl), secret, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "VERCEL_BLOB_CONTROL_URL")
    AudioAssemblyStorage vercelBlobAudioAssemblyStorage(PrivateAudioStoragePort privateAudioStoragePort) {
        return new PrivateAudioAssemblyStorage(privateAudioStoragePort);
    }
}
