package com.meetingautomation.api.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.api.processing.AudioAssemblyUseCase;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.MeetingSessionStatusResponse;
import com.meetingautomation.api.session.UploadPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AudioDownloadServiceTests {
    private static final String SESSION = "ms_download_test";
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void streamsValidatedAttachmentAndMarksDownloadOnlyAfterCompleteCopy() throws Exception {
        byte[] audio = {1, 2, 3, 4};
        TestPrivateAudioStorage objects = new TestPrivateAudioStorage();
        PrivateAudioAssemblyStorage assemblyStorage = new PrivateAudioAssemblyStorage(objects);
        storeAssembly(objects, audio, NOW.plusSeconds(3600));
        MeetingSessionStore sessions = failedSession(true, NOW.plusSeconds(3600));
        AudioDownloadService service = new AudioDownloadService(sessions,
                new AudioAssemblyUseCase(assemblyStorage, 60), Clock.fixed(NOW, ZoneOffset.UTC));

        AudioDownloadService.Download download = service.download(SESSION);
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        download.body().writeTo(responseBody);

        assertThat(download.mimeType()).isEqualTo(MediaType.parseMediaType("audio/webm"));
        assertThat(download.filename()).isEqualTo("meeting-audio.webm");
        assertThat(responseBody.toByteArray()).containsExactly(audio);
        assertThat(sessions.find(SESSION).orElseThrow().processingFailure().downloadCompleted()).isTrue();
        assertThat(sessions.find(SESSION).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void rejectsExpiredOrMissingAssembledAudioBeforeOpeningObjectStream() throws Exception {
        TestPrivateAudioStorage objects = new TestPrivateAudioStorage();
        PrivateAudioAssemblyStorage assemblyStorage = new PrivateAudioAssemblyStorage(objects);
        storeAssembly(objects, new byte[] {9}, NOW);
        MeetingSessionStore sessions = failedSession(true, NOW);
        AudioDownloadService service = new AudioDownloadService(sessions,
                new AudioAssemblyUseCase(assemblyStorage, 60), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.download(SESSION)).isInstanceOf(AudioNotAvailableException.class);
        objects.clearSession(SESSION);
        assertThatThrownBy(() -> service.download(SESSION)).isInstanceOf(AudioNotAvailableException.class);
    }

    @Test
    void doesNotStreamAudioWhenFailureIsFinalizedBeforeAsyncDownloadStarts() throws Exception {
        byte[] audio = {1, 2, 3, 4};
        TestPrivateAudioStorage objects = new TestPrivateAudioStorage();
        PrivateAudioAssemblyStorage assemblyStorage = new PrivateAudioAssemblyStorage(objects);
        storeAssembly(objects, audio, NOW.plusSeconds(3600));
        MeetingSessionStore sessions = failedSession(true, NOW.plusSeconds(3600));
        AudioDownloadService service = new AudioDownloadService(sessions,
                new AudioAssemblyUseCase(assemblyStorage, 60), Clock.fixed(NOW, ZoneOffset.UTC));

        AudioDownloadService.Download download = service.download(SESSION);
        sessions.transitionStatus(SESSION, "PROCESSING_FAILED", "COMPLETED_WITH_WARNINGS");
        ByteArrayOutputStream responseBody = new ByteArrayOutputStream();

        assertThatThrownBy(() -> download.body().writeTo(responseBody)).isInstanceOf(java.io.IOException.class);
        assertThat(responseBody.size()).isZero();
        assertThat(sessions.find(SESSION).orElseThrow().processingFailure().downloadCompleted()).isFalse();
    }

    private static MeetingSessionStore failedSession(boolean audioAvailable, Instant expiresAt) {
        MeetingSessionStore sessions = new MeetingSessionStore();
        sessions.save(new MeetingSession(SESSION, 1, "UPLOADING", 1,
                new MeetingSession.Meeting("Synthetic meeting", "default.md", "1.0.0", List.of("p1"),
                        "UTC", List.of(new MeetingSession.ParticipantReference("p1", "Synthetic participant")))),
                null, new UploadPolicy(15, 64, List.of("audio/webm")));
        sessions.startProcessing(SESSION);
        sessions.failProcessing(SESSION, "TRANSCRIPTION", 33, "TRANSCRIPTION_FAILED", true,
                audioAvailable, false, expiresAt);
        return sessions;
    }

    private static void storeAssembly(TestPrivateAudioStorage objects, byte[] audio, Instant expiry) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio));
        objects.store(SESSION, PrivateAudioStoragePort.ObjectType.ASSEMBLED, -1, "audio/webm", expiry,
                new ByteArrayInputStream(audio), audio.length, sha);
    }
}
