package com.meetingautomation.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.TranscriptSegment;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class MeetingSessionStoreTests {
    @Test
    void findAllReturnsIndependentSnapshotOfSessionsForRecoveryListing() {
        MeetingSessionStore store = new MeetingSessionStore();
        store.save(session("PROCESSING", 1), null);
        store.save(new MeetingSession("failed", 4, "PROCESSING_FAILED", 1,
                new MeetingSession.Meeting("Failed meeting", "template", List.of(), "UTC"),
                "TRANSCRIPTION", 34, List.of(), List.of(),
                new ProcessingFailure("TRANSCRIPTION", "TRANSCRIPTION_PROCESSING_FAILED", true,
                        true, java.time.Instant.now().plusSeconds(3600), false, false)), null);

        List<MeetingSession> snapshot = store.findAll();

        assertThat(snapshot).extracting(MeetingSession::sessionId).containsExactlyInAnyOrder("session", "failed");
        assertThatThrownBy(() -> snapshot.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void distinctMissingSessionsReuseAFixedBoundedLockTable() throws Exception {
        MeetingSessionStore store = new MeetingSessionStore();
        Field locksField = MeetingSessionStore.class.getDeclaredField("sessionLockStripes");
        locksField.setAccessible(true);
        ReentrantLock[] stripes = (ReentrantLock[]) locksField.get(store);
        assertThat(stripes).hasSizeBetween(1, 256);

        for (int index = 0; index < 10_000; index++) {
            String missingSessionId = "missing-session-" + index;
            store.withSessionLock(missingSessionId, () -> null);
        }

        assertThat(locksField.get(store)).isSameAs(stripes);
        assertThat(((ReentrantLock[]) locksField.get(store))).hasSize(stripes.length);
    }

    @Test
    void concurrentUpdatesUsingOneVersionAllowExactlyOneCompleteMappingSetToCommit() throws Exception {
        MeetingSessionStore store = new MeetingSessionStore();
        store.save(session("REVIEW", 7), null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> update(store, "pt_ada", ready, start));
            var second = workers.submit(() -> update(store, "pt_grace", ready, start));
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = List.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("COMMITTED", "STALE");
        }
        MeetingSession current = store.find("session").orElseThrow();
        assertThat(current.version()).isEqualTo(8);
        assertThat(current.speakers()).hasSize(2);
        assertThat(current.speakers()).extracting(Speaker::participantId)
                .containsExactly(current.speakers().getFirst().participantId(), current.speakers().getFirst().participantId());
        assertThat(current.speakers().getFirst().participantId()).isIn("pt_ada", "pt_grace");
        assertThat(current.transcript()).containsExactly(new TranscriptSegment("seg", "speaker_a", 1, 2, "text"));
    }

    @Test
    void stateConflictTakesPrecedenceOverStaleVersionAtCommit() {
        MeetingSessionStore store = new MeetingSessionStore();
        store.save(session("PROCESSING", 8), null);

        assertThatThrownBy(() -> store.replaceSpeakerMappings("session", 7,
                List.of(new Speaker("speaker_a", "A", "pt_ada"))))
                .isInstanceOf(SessionStateConflictException.class);
        assertThat(store.find("session").orElseThrow().version()).isEqualTo(8);
        assertThat(store.find("session").orElseThrow().speakers())
                .containsExactly(new Speaker("speaker_a", "A"), new Speaker("speaker_b", "B"));
    }

    private static String update(MeetingSessionStore store, String participantId,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("Concurrent update did not start");
            store.replaceSpeakerMappings("session", 7,
                    List.of(new Speaker("speaker_a", "A", participantId),
                            new Speaker("speaker_b", "B", participantId)));
            return "COMMITTED";
        } catch (SessionVersionConflictException stale) {
            return "STALE";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static MeetingSession session(String status, int version) {
        return new MeetingSession("session", version, status, 1,
                new MeetingSession.Meeting("Meeting", "template", List.of("pt_ada", "pt_grace"), "UTC"),
                status.equals("REVIEW") ? "DRAFT_READY" : "TRANSCRIPTION", 100,
                List.of(new Speaker("speaker_a", "A"), new Speaker("speaker_b", "B")),
                List.of(new TranscriptSegment("seg", "speaker_a", 1, 2, "text")), null);
    }
}
