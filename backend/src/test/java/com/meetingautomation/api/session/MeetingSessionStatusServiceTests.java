package com.meetingautomation.api.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeetingSessionStatusServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void expiredAudioIsNotAdvertisedAsAvailableOrDownloadableAtTheExactDeadline() {
        MeetingSessionStore store = new MeetingSessionStore();
        store.save(new MeetingSession("ms_expiry", 4, "PROCESSING_FAILED", 1,
                new MeetingSession.Meeting("Planning", "default.md", List.of(), "UTC"), "TRANSCRIPTION", 34,
                List.of(), List.of(), new ProcessingFailure("TRANSCRIPTION", "TRANSCRIPTION_FAILED", true,
                        true, NOW, false, false)), null);

        var response = new MeetingSessionStatusService(store, Clock.fixed(NOW, ZoneOffset.UTC)).get("ms_expiry");

        assertThat(response.data().processing().audioAvailable()).isFalse();
        assertThat(response.data().allowedActions()).containsExactly("FINALIZE_PROCESSING_FAILURE");
        assertThat(response.data().processing().audioExpiresAt()).isEqualTo(NOW);
    }
}
