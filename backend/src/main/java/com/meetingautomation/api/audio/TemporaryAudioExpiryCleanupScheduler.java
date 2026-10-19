package com.meetingautomation.api.audio;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically removes expired session directories from Render's temporary filesystem. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TemporaryAudioExpiryCleanupScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TemporaryAudioExpiryCleanupScheduler.class);
    private final AudioAssemblyFileStore storage;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TemporaryAudioExpiryCleanupScheduler(AudioAssemblyFileStore storage) {
        this(storage, Clock.systemUTC());
    }

    TemporaryAudioExpiryCleanupScheduler(AudioAssemblyFileStore storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${TEMP_AUDIO_CLEANUP_INTERVAL_MS:3600000}",
            initialDelayString = "${TEMP_AUDIO_CLEANUP_INITIAL_DELAY_MS:60000}")
    public void sweep() {
        try {
            storage.deleteExpiredSessions(clock.instant());
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("stage", "AUDIO_CLEANUP")
                    .addKeyValue("errorCode", "AUDIO_STORAGE_FAILED")
                    .log("Expired temporary Audio cleanup will be retried.");
        }
    }
}
