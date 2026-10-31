package com.meetingautomation.api.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessingFailureListControllerTests {
    @Test
    void listsOnlyFailedSessionsAndProjectsRetryAvailabilityFromServerActions() {
        MeetingSessionStore store = new MeetingSessionStore();
        store.save(new MeetingSession("processing", 1, "PROCESSING", 1,
                new MeetingSession.Meeting("Still processing", "template", List.of(), "UTC")), null);
        Instant expiry = Instant.now().plusSeconds(3600);
        store.save(new MeetingSession("failed", 7, "PROCESSING_FAILED", 1,
                new MeetingSession.Meeting("Long planning", "template", List.of("participant"), "UTC"),
                "TRANSCRIPTION", 34, List.of(), List.of(),
                new ProcessingFailure("TRANSCRIPTION", "TRANSCRIPTION_PROCESSING_FAILED", true,
                        true, expiry, false, false)), null);

        ProcessingFailureListResponse result = new ProcessingFailureListController(store).list();

        assertThat(result.data().items()).hasSize(1);
        var item = result.data().items().getFirst();
        assertThat(item.sessionId()).isEqualTo("failed");
        assertThat(item.version()).isEqualTo(7);
        assertThat(item.title()).isEqualTo("Long planning");
        assertThat(item.stage()).isEqualTo("TRANSCRIPTION");
        assertThat(item.errorCode()).isEqualTo("TRANSCRIPTION_PROCESSING_FAILED");
        assertThat(item.audioExpiresAt()).isEqualTo(expiry);
        assertThat(item.allowedActions()).contains("RETRY_PROCESSING");
    }
}
