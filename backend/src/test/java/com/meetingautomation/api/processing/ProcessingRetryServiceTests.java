package com.meetingautomation.api.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.api.audio.PrivateAudioAssemblyStorage;
import com.meetingautomation.api.audio.PrivateAudioStoragePort;
import com.meetingautomation.api.audio.TestPrivateAudioStorage;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.SessionVersionConflictException;
import com.meetingautomation.api.session.UploadPolicy;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessingRetryServiceTests {
    private static final String SESSION = "ms_retry_test";
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void retriesAllowedStageOnceAndRenewsObjectLeaseForActiveProcessing() throws Exception {
        Fixture fixture = new Fixture(NOW.plusSeconds(3600));

        ProcessingStartResponse first = fixture.service.retry(SESSION, "\"3\"", "retry-one");
        ProcessingStartResponse replay = fixture.service.retry(SESSION, "\"3\"", "retry-one");

        assertThat(first).isEqualTo(replay);
        assertThat(first.data().stage()).isEqualTo("TRANSCRIPTION");
        assertThat(fixture.queue).containsExactly(fixture.job);
        assertThat(fixture.sessions.find(SESSION).orElseThrow().status()).isEqualTo("PROCESSING");
        assertThat(fixture.storage.listForSession(SESSION)).allSatisfy(object ->
                assertThat(object.expiresAt()).isEqualTo(NOW.plusSeconds(24 * 60 * 60)));
    }

    @Test
    void rejectsStaleVersionAndExpiredAudioWithoutQueueing() throws Exception {
        Fixture stale = new Fixture(NOW.plusSeconds(3600));
        assertThatThrownBy(() -> stale.service.retry(SESSION, "\"2\"", "retry-stale"))
                .isInstanceOf(SessionVersionConflictException.class);
        assertThat(stale.queue).isEmpty();

        Fixture expired = new Fixture(NOW);
        assertThatThrownBy(() -> expired.service.retry(SESSION, "\"3\"", "retry-expired"))
                .isInstanceOf(com.meetingautomation.api.audio.AudioNotAvailableException.class);
        assertThat(expired.queue).isEmpty();
    }

    private static final class Fixture {
        private final MeetingSessionStore sessions = new MeetingSessionStore();
        private final TestPrivateAudioStorage storage = new TestPrivateAudioStorage();
        private final List<ProcessingJob> queue = new ArrayList<>();
        private final ProcessingJob job = new ProcessingJob(SESSION, 1, 15_000, "audio/webm");
        private final ProcessingRetryService service;

        private Fixture(Instant expiresAt) throws Exception {
            sessions.save(new MeetingSession(SESSION, 1, "UPLOADING", 1,
                    new MeetingSession.Meeting("Synthetic", "default.md", "1.0.0", List.of("p1"), "UTC",
                            List.of(new MeetingSession.ParticipantReference("p1", "Synthetic")))),
                    null, new UploadPolicy(15, 64, List.of("audio/webm")));
            sessions.startProcessing(SESSION);
            sessions.failProcessing(SESSION, "TRANSCRIPTION", 33, "TRANSCRIPTION_FAILED", true,
                    true, false, expiresAt);
            sessions.registerProcessingJob(job);
            byte[] audio = {1, 2, 3};
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio));
            storage.store(SESSION, PrivateAudioStoragePort.ObjectType.ASSEMBLED, -1, "audio/webm", expiresAt,
                    new ByteArrayInputStream(audio), audio.length, sha);
            AudioAssemblyUseCase useCase = new AudioAssemblyUseCase(new PrivateAudioAssemblyStorage(storage), 60);
            service = new ProcessingRetryService(sessions, queue::add, useCase, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}
