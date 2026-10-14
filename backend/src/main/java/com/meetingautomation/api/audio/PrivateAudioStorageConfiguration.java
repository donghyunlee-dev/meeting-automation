package com.meetingautomation.api.audio;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Creates the private object adapter only when a bucket is explicitly configured. */
@Configuration
@EnableScheduling
public class PrivateAudioStorageConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "PRIVATE_AUDIO_S3_BUCKET")
    S3Client privateAudioS3Client(
            @Value("${PRIVATE_AUDIO_S3_REGION:${AWS_REGION:us-east-1}}") String region,
            @Value("${PRIVATE_AUDIO_S3_ENDPOINT:}") String endpoint) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "PRIVATE_AUDIO_S3_BUCKET")
    PrivateAudioStoragePort privateAudioStoragePort(S3Client privateAudioS3Client,
            @Value("${PRIVATE_AUDIO_S3_BUCKET}") String bucket,
            @Value("${PRIVATE_AUDIO_S3_PREFIX:meeting-audio}") String prefix) {
        return new S3PrivateAudioStorageAdapter(privateAudioS3Client, bucket, Clock.systemUTC(), prefix);
    }

    @Bean
    @ConditionalOnProperty(name = "PRIVATE_AUDIO_S3_BUCKET")
    AudioAssemblyStorage privateAudioAssemblyStorage(PrivateAudioStoragePort privateAudioStoragePort) {
        return new PrivateAudioAssemblyStorage(privateAudioStoragePort);
    }
}
