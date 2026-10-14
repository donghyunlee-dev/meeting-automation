package com.meetingautomation;

import com.meetingautomation.api.audio.PrivateAudioStoragePort;
import com.meetingautomation.api.audio.TestPrivateAudioStorage;
import com.meetingautomation.api.audio.AudioAssemblyStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Test-only synthetic object storage; production never falls back to process-local Audio storage. */
@Configuration
class PrivateAudioTestConfiguration {
    @Bean
    @Primary
    TestPrivateAudioStorage privateAudioStoragePortForTests() {
        return new TestPrivateAudioStorage();
    }

    @Bean
    AudioAssemblyStorage audioAssemblyStorageForTests(PrivateAudioStoragePort privateAudioStoragePortForTests) {
        return new com.meetingautomation.api.audio.PrivateAudioAssemblyStorage(privateAudioStoragePortForTests);
    }
}
