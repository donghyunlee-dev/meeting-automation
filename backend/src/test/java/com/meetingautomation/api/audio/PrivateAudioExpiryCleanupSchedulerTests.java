package com.meetingautomation.api.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.meetingautomation.api.processing.AudioAssemblyUseCase;
import com.meetingautomation.api.processing.FailureDocumentWriter;
import com.meetingautomation.api.processing.FailureFinalizeService;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.UploadPolicy;
import com.meetingautomation.document.MeetingDocumentRef;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class PrivateAudioExpiryCleanupSchedulerTests {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void sweepDeletesExpiredObjectsWhenSessionIsMissingAfterRestartAndContinuesAfterDeleteFailure() {
        var expired = new PrivateAudioStoragePort.StoredObject("opaque-1", "ms_one",
                PrivateAudioStoragePort.ObjectType.CHUNK, 0, "audio/webm", 1, "a".repeat(64), NOW);
        var alsoExpired = new PrivateAudioStoragePort.StoredObject("opaque-2", "ms_two",
                PrivateAudioStoragePort.ObjectType.ASSEMBLED, 0, "audio/webm", 1, "b".repeat(64), NOW);
        FakeStorage storage = new FakeStorage(List.of(expired, alsoExpired));
        storage.failDeleteFor = "opaque-1";

        new PrivateAudioExpiryCleanupScheduler(storage, Clock.fixed(NOW, ZoneOffset.UTC), Optional.empty(),
                Optional.of(new MeetingSessionStore())).sweep();

        assertThat(storage.requestedExpiryCutoff).isEqualTo(NOW);
        assertThat(storage.deleted).containsExactly("opaque-1", "opaque-2");
    }

    @Test
    void sweepPreservesAudioWhenRetryClearsExpiryAfterExpiredSnapshotWasRead() {
        var expiredSnapshot = new PrivateAudioStoragePort.StoredObject("opaque-active", "ms_retrying",
                PrivateAudioStoragePort.ObjectType.CHUNK, 0, "audio/webm", 1, "c".repeat(64), NOW);
        FakeStorage storage = new FakeStorage(List.of(expiredSnapshot));
        storage.setCurrentExpiry("opaque-active", null);

        new PrivateAudioExpiryCleanupScheduler(storage, Clock.fixed(NOW, ZoneOffset.UTC)).sweep();

        assertThat(storage.deleted).isEmpty();
        assertThat(storage.find("opaque-active").orElseThrow().expiresAt()).isNull();
    }

    @Test
    void sweepPreservesExpiredObjectsWhileTheInMemorySessionIsProcessing() {
        var expiredSnapshot = new PrivateAudioStoragePort.StoredObject("opaque-processing", "ms_processing",
                PrivateAudioStoragePort.ObjectType.ASSEMBLED, -1, "audio/webm", 1, "d".repeat(64), NOW);
        FakeStorage storage = new FakeStorage(List.of(expiredSnapshot));
        MeetingSessionStore sessions = new MeetingSessionStore();
        sessions.save(new MeetingSession("ms_processing", 1, "UPLOADING", 1,
                new MeetingSession.Meeting("Synthetic", "default.md", List.of("p1"), "UTC")), null);
        sessions.startProcessing("ms_processing");

        new PrivateAudioExpiryCleanupScheduler(storage, Clock.fixed(NOW, ZoneOffset.UTC), Optional.empty(),
                Optional.of(sessions)).sweep();

        assertThat(sessions.find("ms_processing").orElseThrow().status()).isEqualTo("PROCESSING");
        assertThat(storage.deleted).isEmpty();
        assertThat(storage.find("opaque-processing")).isPresent();
    }

    @Test
    void productionConfigurationRegistersTheExpirySchedulerWithoutStartingAService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PrivateAudioStorageConfiguration.class, PrivateAudioExpiryCleanupScheduler.class,
                    SyntheticStorageConfiguration.class);
            context.refresh();

            assertThat(context.getBean(PrivateAudioExpiryCleanupScheduler.class)).isNotNull();
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class)).isNotNull();
        }
    }

    @Test
    void sweepAutoFinalizesExpiredFailureWhileSessionIsAvailableBeforeDeletingAudio() throws Exception {
        TestPrivateAudioStorage storage = new TestPrivateAudioStorage();
        byte[] bytes = {7, 8, 9};
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        storage.store("ms_live", PrivateAudioStoragePort.ObjectType.ASSEMBLED, -1, "audio/webm", NOW,
                new ByteArrayInputStream(bytes), bytes.length, digest);
        MeetingSessionStore sessions = new MeetingSessionStore();
        sessions.save(new MeetingSession("ms_live", 1, "UPLOADING", 1,
                new MeetingSession.Meeting("Synthetic", "default.md", List.of("p1"), "UTC")), null,
                new UploadPolicy(15, 64, List.of("audio/webm")));
        sessions.startProcessing("ms_live");
        sessions.failProcessing("ms_live", "TRANSCRIPTION", 33, "TRANSCRIPTION_FAILED", true,
                true, false, NOW);
        FailureDocumentWriter writer = mock(FailureDocumentWriter.class);
        when(writer.write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("DISCARDED")))
                .thenReturn(new MeetingDocumentRef("doc-safe-id", "https://docs.example/safe"));
        FailureFinalizeService service = new FailureFinalizeService(sessions, writer,
                new AudioAssemblyUseCase(new PrivateAudioAssemblyStorage(storage), 60));

        new PrivateAudioExpiryCleanupScheduler(storage, Clock.fixed(NOW, ZoneOffset.UTC), Optional.of(service)).sweep();

        assertThat(sessions.find("ms_live").orElseThrow().status()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(sessions.findFailureFinalization("ms_live")).isPresent();
        assertThat(storage.listForSession("ms_live")).isEmpty();
        verify(writer).write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("DISCARDED"));
    }

    @Test
    void sweepRetainsExpiredAudioAndRetriesFailureDocumentOnNextPass() throws Exception {
        TestPrivateAudioStorage storage = new TestPrivateAudioStorage();
        byte[] bytes = {7, 8, 9};
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        storage.store("ms_retry", PrivateAudioStoragePort.ObjectType.ASSEMBLED, -1, "audio/webm", NOW,
                new ByteArrayInputStream(bytes), bytes.length, digest);
        MeetingSessionStore sessions = new MeetingSessionStore();
        sessions.save(new MeetingSession("ms_retry", 1, "UPLOADING", 1,
                new MeetingSession.Meeting("Synthetic", "default.md", List.of("p1"), "UTC")), null,
                new UploadPolicy(15, 64, List.of("audio/webm")));
        sessions.startProcessing("ms_retry");
        sessions.failProcessing("ms_retry", "TRANSCRIPTION", 33, "TRANSCRIPTION_FAILED", true,
                true, false, NOW);
        FailureDocumentWriter writer = mock(FailureDocumentWriter.class);
        when(writer.write(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("DISCARDED")))
                .thenThrow(new IllegalStateException("private provider response"))
                .thenReturn(new MeetingDocumentRef("doc-safe-id", "https://docs.example/safe"));
        FailureFinalizeService service = new FailureFinalizeService(sessions, writer,
                new AudioAssemblyUseCase(new PrivateAudioAssemblyStorage(storage), 60));
        PrivateAudioExpiryCleanupScheduler scheduler = new PrivateAudioExpiryCleanupScheduler(storage,
                Clock.fixed(NOW, ZoneOffset.UTC), Optional.of(service));

        scheduler.sweep();

        assertThat(sessions.find("ms_retry").orElseThrow().status()).isEqualTo("PROCESSING_FAILED");
        assertThat(storage.listForSession("ms_retry")).hasSize(1)
                .allSatisfy(object -> assertThat(object.expiresAt()).isEqualTo(NOW));

        scheduler.sweep();

        assertThat(sessions.find("ms_retry").orElseThrow().status()).isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(storage.listForSession("ms_retry")).isEmpty();
        verify(writer, org.mockito.Mockito.times(2)).write(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("DISCARDED"));
    }

    @Configuration
    static class SyntheticStorageConfiguration {
        @Bean PrivateAudioStoragePort privateAudioStoragePort() { return new TestPrivateAudioStorage(); }
    }

    private static final class FakeStorage implements PrivateAudioStoragePort {
        private final List<StoredObject> expired;
        private final java.util.Map<String, StoredObject> current = new java.util.HashMap<>();
        private final List<String> deleted = new ArrayList<>();
        private Instant requestedExpiryCutoff;
        private String failDeleteFor;

        private FakeStorage(List<StoredObject> expired) {
            this.expired = expired;
            expired.forEach(object -> current.put(object.objectId(), object));
        }
        private void setCurrentExpiry(String objectId, Instant expiresAt) {
            StoredObject prior = current.get(objectId);
            current.put(objectId, new StoredObject(prior.objectId(), prior.sessionId(), prior.type(), prior.sequence(),
                    prior.mimeType(), prior.bytes(), prior.sha256(), expiresAt));
        }
        @Override public StoredObject store(String sessionId, ObjectType type, int sequence, String mimeType,
                Instant expiresAt, InputStream body, long bytes, String sha256) { throw new UnsupportedOperationException(); }
        @Override public InputStream open(String objectId) { return new ByteArrayInputStream(new byte[0]); }
        @Override public Optional<StoredObject> find(String objectId) { return Optional.ofNullable(current.get(objectId)); }
        @Override public List<StoredObject> listForSession(String sessionId) { return List.of(); }
        @Override public List<StoredObject> listExpired(Instant now) {
            requestedExpiryCutoff = now;
            return expired;
        }
        @Override public void extendExpiry(String objectId, Instant expiresAt) { throw new UnsupportedOperationException(); }
        @Override public void delete(String objectId) {
            deleted.add(objectId);
            if (objectId.equals(failDeleteFor)) throw new AudioStorageException();
            current.remove(objectId);
        }
    }
}
