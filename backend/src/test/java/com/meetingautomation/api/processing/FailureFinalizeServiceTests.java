package com.meetingautomation.api.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meetingautomation.api.audio.PrivateAudioStoragePort;
import com.meetingautomation.api.audio.TestPrivateAudioStorage;
import com.meetingautomation.api.session.FailureFinalization;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.UploadPolicy;
import com.meetingautomation.document.MeetingDocumentRef;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailureFinalizeServiceTests {
    private static final String SESSION = "ms_finalize_test";
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void writesFailureOnlyDocumentThenFinalizesOnceWithoutDeliveryDependencies() throws Exception {
        MeetingSessionStore sessions = failedSession(true, true);
        TestPrivateAudioStorage storage = audio();
        FailureDocumentWriter writer = mock(FailureDocumentWriter.class);
        when(writer.write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new MeetingDocumentRef("doc-safe-id", "https://docs.example/safe"));
        FailureFinalizeService service = new FailureFinalizeService(sessions, writer,
                new AudioAssemblyUseCase(new com.meetingautomation.api.audio.PrivateAudioAssemblyStorage(storage), 60),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        FailureFinalizeResponse first = service.finalizeFailure(SESSION, "\"4\"", "finish-1",
                new FailureFinalizeRequest("DOWNLOADED"));
        FailureFinalizeResponse replay = service.finalizeFailure(SESSION, "\"4\"", "finish-1",
                new FailureFinalizeRequest("DOWNLOADED"));

        assertThat(first).isEqualTo(replay);
        assertThat(first.data().status()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(sessions.findFailureFinalization(SESSION)).get()
                .extracting(FailureFinalization::audioDisposition).isEqualTo("DOWNLOADED");
        assertThat(storage.listForSession(SESSION)).isEmpty();
        verify(writer).write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void downloadDispositionRequiresCompletedDownloadAndWriterFailurePreservesFailureAndAudio() throws Exception {
        MeetingSessionStore sessions = failedSession(true, false);
        TestPrivateAudioStorage storage = audio();
        FailureDocumentWriter writer = mock(FailureDocumentWriter.class);
        FailureFinalizeService service = new FailureFinalizeService(sessions, writer,
                new AudioAssemblyUseCase(new com.meetingautomation.api.audio.PrivateAudioAssemblyStorage(storage), 60),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        assertThatThrownBy(() -> service.finalizeFailure(SESSION, "\"3\"", "finish-1",
                new FailureFinalizeRequest("DOWNLOADED")))
                .isInstanceOf(com.meetingautomation.api.audio.AudioNotAvailableException.class);
        verify(writer, never()).write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(writer.write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("provider body and token"));
        assertThatThrownBy(() -> service.finalizeFailure(SESSION, "\"3\"", "finish-2",
                new FailureFinalizeRequest("DISCARDED")))
                .isInstanceOf(FailureDocumentWriteException.class);
        assertThat(sessions.find(SESSION).orElseThrow().status()).isEqualTo("PROCESSING_FAILED");
        assertThat(storage.listForSession(SESSION)).hasSize(1);
        assertThat(sessions.findFailureFinalization(SESSION)).isEmpty();
    }

    @Test
    void failedAudioDeletionAfterUserFinalizeIsScheduledImmediatelyForCleanupRetry() throws Exception {
        MeetingSessionStore sessions = failedSession(true, false);
        TestPrivateAudioStorage storage = audio();
        storage.failNextDelete();
        FailureDocumentWriter writer = mock(FailureDocumentWriter.class);
        when(writer.write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new MeetingDocumentRef("doc-safe-id", "https://docs.example/safe"));
        FailureFinalizeService service = new FailureFinalizeService(sessions, writer,
                new AudioAssemblyUseCase(new com.meetingautomation.api.audio.PrivateAudioAssemblyStorage(storage), 60),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        FailureFinalizeResponse response = service.finalizeFailure(SESSION, "\"3\"", "finish-delete-failure",
                new FailureFinalizeRequest("DISCARDED"));

        assertThat(response.data().status()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(storage.listForSession(SESSION)).hasSize(1)
                .allSatisfy(object -> assertThat(object.expiresAt()).isEqualTo(NOW));
    }

    @Test
    void expirySweepAutoFinalizesLiveFailedSessionBeforeDeletingExpiredAudio() throws Exception {
        MeetingSessionStore sessions = failedSession(true, false, NOW);
        TestPrivateAudioStorage storage = audio(NOW);
        FailureDocumentWriter writer = mock(FailureDocumentWriter.class);
        when(writer.write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("DISCARDED")))
                .thenReturn(new MeetingDocumentRef("doc-safe-id", "https://docs.example/safe"));
        FailureFinalizeService service = new FailureFinalizeService(sessions, writer,
                new AudioAssemblyUseCase(new com.meetingautomation.api.audio.PrivateAudioAssemblyStorage(storage), 60),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        assertThat(service.autoFinalizeExpired(SESSION, NOW)).isTrue();

        assertThat(sessions.find(SESSION).orElseThrow().status()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(sessions.findFailureFinalization(SESSION)).get()
                .extracting(FailureFinalization::audioDisposition).isEqualTo("DISCARDED");
        assertThat(storage.listForSession(SESSION)).isEmpty();
        verify(writer).write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("DISCARDED"));
    }

    @Test
    void expirySweepKeepsExpiredAudioWhenFailureDocumentWriteFailsForLaterRetry() throws Exception {
        MeetingSessionStore sessions = failedSession(true, false, NOW);
        TestPrivateAudioStorage storage = audio(NOW);
        FailureDocumentWriter writer = mock(FailureDocumentWriter.class);
        when(writer.write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("DISCARDED")))
                .thenThrow(new IllegalStateException("provider response body"));
        FailureFinalizeService service = new FailureFinalizeService(sessions, writer,
                new AudioAssemblyUseCase(new com.meetingautomation.api.audio.PrivateAudioAssemblyStorage(storage), 60),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        assertThatThrownBy(() -> service.autoFinalizeExpired(SESSION, NOW))
                .isInstanceOf(FailureDocumentWriteException.class);

        assertThat(sessions.find(SESSION).orElseThrow().status()).isEqualTo("PROCESSING_FAILED");
        assertThat(storage.listForSession(SESSION)).hasSize(1)
                .allSatisfy(object -> assertThat(object.expiresAt()).isEqualTo(NOW));
        assertThat(sessions.findFailureFinalization(SESSION)).isEmpty();
    }

    private static MeetingSessionStore failedSession(boolean audioAvailable, boolean downloaded) {
        return failedSession(audioAvailable, downloaded, NOW.plusSeconds(3600));
    }

    private static MeetingSessionStore failedSession(boolean audioAvailable, boolean downloaded, Instant expiresAt) {
        MeetingSessionStore sessions = new MeetingSessionStore();
        sessions.save(new MeetingSession(SESSION, 1, "UPLOADING", 1,
                new MeetingSession.Meeting("Synthetic", "default.md", "1.0.0", List.of("p1"), "UTC",
                        List.of(new MeetingSession.ParticipantReference("p1", "Synthetic")))),
                null, new UploadPolicy(15, 64, List.of("audio/webm")));
        sessions.startProcessing(SESSION);
        sessions.failProcessing(SESSION, "TRANSCRIPTION", 33, "TRANSCRIPTION_FAILED", true,
                audioAvailable, false, expiresAt);
        if (downloaded) sessions.markAudioDownloadCompleted(SESSION);
        return sessions;
    }

    private static TestPrivateAudioStorage audio() throws Exception {
        return audio(NOW.plusSeconds(3600));
    }

    private static TestPrivateAudioStorage audio(Instant expiresAt) throws Exception {
        TestPrivateAudioStorage storage = new TestPrivateAudioStorage();
        byte[] bytes = {1, 2, 3};
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        storage.store(SESSION, PrivateAudioStoragePort.ObjectType.ASSEMBLED, -1, "audio/webm", expiresAt,
                new ByteArrayInputStream(bytes), bytes.length, sha);
        return storage;
    }
}
