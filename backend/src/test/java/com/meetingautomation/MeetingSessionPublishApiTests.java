package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.ProviderHealth;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.document.transfer.DocumentTransferPort;
import com.meetingautomation.document.transfer.TransferRecord;
import com.meetingautomation.document.transfer.TransferManifest;
import com.meetingautomation.document.transfer.TransferCodec;
import com.meetingautomation.document.settings.DocumentProviderFactory;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"DOCUMENT_PROVIDER=", "NOTIFICATION_PROVIDER=SLACK", "OPENAI_API_KEY="})
@AutoConfigureMockMvc
@Import({MeetingSessionPublishApiTests.TestFixtures.class,
        ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class MeetingSessionPublishApiTests {
    private static final String SESSION_URL = "/api/v1/meeting-sessions/{sessionId}";
    private static final String PUBLISH_URL = SESSION_URL + "/publish";
    private static final String SESSION_ID = "ms_publish-" + UUID.randomUUID();
    private static final String REQUEST = """
            {"emailRecipientParticipantIds":["pt_ada"],"notificationEnabled":true}
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private MeetingSessionStore sessionStore;
    @Autowired private ManualPublishExecutor workerExecutor;
    @Autowired private GlobalSettingsStore globalSettingsStore;
    @Autowired private DocumentConnectionGate gate;

    @MockitoBean private DocumentProviderFactory providerFactory;

    @BeforeEach
    void saveConfirmedSessionAndResetQueue() {
        sessionStore.remove(SESSION_ID);
        workerExecutor.reset();
        FakePublishProvider.PUBLISH_CALLS.set(0);
        when(providerFactory.create(any())).thenReturn(new FakePublishProvider());
        sessionStore.save(new MeetingSession(SESSION_ID, 7, "CONFIRMED", 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0", List.of("pt_ada", "pt_grace"),
                        "Asia/Seoul", List.of(new MeetingSession.ParticipantReference("pt_ada", "Ada"),
                                new MeetingSession.ParticipantReference("pt_grace", "Grace"))),
                "DRAFT_READY", 100,
                List.of(new Speaker("speaker_1", "Speaker one", "pt_ada")),
                List.of(new TranscriptSegment("seg_1", "speaker_1", 0, 1000, "Discuss launch")), null,
                new StructuredMinutes("default.md", "1.0.0", "Summary", List.of("Discussion"),
                        List.of("Decision"), List.of(new StructuredMinutes.ActionItem(
                                "pt_grace", "Follow up", LocalDate.of(2026, 10, 20))), List.of("Next")),
                Instant.parse("2026-10-01T03:00:00Z")), null);
    }

    @Test
    void validPublishReturns202AndAtomicallyQueuesOneWorkerForSameKeyReplay() throws Exception {
        String first = mockMvc.perform(publish(SESSION_ID, "7", "publish-once", REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.status").value("PUBLISHING"))
                .andReturn().getResponse().getContentAsString();

        MeetingSession accepted = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(accepted.status()).isEqualTo("PUBLISHING");
        assertThat(accepted.version()).isEqualTo(8);
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isZero();

        String replay = mockMvc.perform(publish(SESSION_ID, "7", "publish-once", REQUEST))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(accepted);
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        assertThat(globalSettingsStore.read().active().connectionVersion()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gate.beginSwitch(globalSettingsStore.read().version()))
                .isInstanceOf(com.meetingautomation.document.settings.SettingsException.class);
        workerExecutor.drain();
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isEqualTo(1);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).document().documentId()).isEqualTo("doc_publish");
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).recipients()).containsExactly("pt_ada");
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).notificationEnabled()).isTrue();
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publish.documentId").value("doc_publish"))
                .andExpect(jsonPath("$.data.publish.documentUrl").value("https://docs.example/doc_publish"));
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) {
            assertThat(switchLease).isNotNull();
        }
    }

    @Test
    void malformedBodyAndRosterRecipientAreRejectedWithoutChangingSessionOrQueue() throws Exception {
        MeetingSession before = sessionStore.find(SESSION_ID).orElseThrow();
        for (String body : List.of("", "{", "{}", "null",
                "{\"emailRecipientParticipantIds\":null,\"notificationEnabled\":true}",
                "{\"emailRecipientParticipantIds\":[],\"notificationEnabled\":null}",
                "{\"emailRecipientParticipantIds\":[\"pt_ada\",\"pt_ada\"],\"notificationEnabled\":true}",
                "{\"emailRecipientParticipantIds\":[],\"notificationEnabled\":true,\"extra\":1}")) {
            mockMvc.perform(publish(SESSION_ID, "7", "publish-invalid-" + UUID.randomUUID(), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(publish(SESSION_ID, "7", "publish-outside-roster",
                        "{\"emailRecipientParticipantIds\":[\"pt_outside\"],\"notificationEnabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(before);
        assertThat(workerExecutor.pendingCount()).isZero();
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isZero();
        mockMvc.perform(post(PUBLISH_URL, SESSION_ID).contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isBadRequest());
        mockMvc.perform(publish(SESSION_ID, "not-an-etag", "bad-etag", REQUEST))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotencyKeyConflictAndSwitchAlreadyHeldDoNotMutateSession() throws Exception {
        MeetingSession before = sessionStore.find(SESSION_ID).orElseThrow();
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) {
            mockMvc.perform(publish(SESSION_ID, "7", "switch-held", REQUEST))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("DOCUMENT_CHANGE_BUSY"));
            assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(before);
            assertThat(workerExecutor.pendingCount()).isZero();
        }
        mockMvc.perform(publish(SESSION_ID, "7", "conflict-key", REQUEST)).andExpect(status().isAccepted());
        mockMvc.perform(publish(SESSION_ID, "7", "conflict-key",
                        "{\"emailRecipientParticipantIds\":[],\"notificationEnabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        workerExecutor.drain();
    }

    @Test
    void differentKeyWhilePublishIsPendingIsRejectedWithoutSecondWorker() throws Exception {
        mockMvc.perform(publish(SESSION_ID, "7", "publish-key-one", REQUEST)).andExpect(status().isAccepted());
        mockMvc.perform(publish(SESSION_ID, "7", "publish-key-two", REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        assertThat(workerExecutor.pendingCount()).isEqualTo(1);
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isZero();
        workerExecutor.drain();
        assertThat(FakePublishProvider.PUBLISH_CALLS.get()).isEqualTo(1);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
    }

    @Test
    void missingSessionStateConflictAndStaleVersionDoNotQueuePublish() throws Exception {
        mockMvc.perform(publish("ms_publish_missing", "1", "missing-session-key", REQUEST))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        sessionStore.save(new MeetingSession("ms_publish_review", 7, "REVIEW", 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0", List.of("pt_ada"),
                        "Asia/Seoul", List.of(new MeetingSession.ParticipantReference("pt_ada", "Ada"))),
                "DRAFT_READY", 100, List.of(), List.of(), null,
                new StructuredMinutes("default.md", "1.0.0", "Summary", List.of(), List.of(), List.of(), List.of())), null);
        mockMvc.perform(publish("ms_publish_review", "7", "review-session-key", REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));

        mockMvc.perform(publish(SESSION_ID, "6", "stale-session-key", REQUEST))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("SESSION_VERSION_CONFLICT"));
        assertThat(workerExecutor.pendingCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("CONFIRMED");
    }

    @Test
    void providerFailureSetsDocumentFailedReleasesLeaseAndRetryCompletes() throws Exception {
        FakePublishProvider failing = new FakePublishProvider(true);
        when(providerFactory.create(any())).thenReturn(failing);
        String first = mockMvc.perform(publish(SESSION_ID, "7", "publish-fail-first", REQUEST))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        workerExecutor.drain();
        MeetingSession failed = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(failed.status()).isEqualTo("DOCUMENT_FAILED");
        assertThat(failed.version()).isEqualTo(9);
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).errorCode()).isEqualTo("DOCUMENT_FAILURE");
        mockMvc.perform(get(SESSION_URL, SESSION_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowedActions[0]").value("PUBLISH"))
                .andExpect(jsonPath("$.data.publish.errorCode").value("DOCUMENT_FAILURE"));
        assertThat(mockMvc.perform(publish(SESSION_ID, "7", "publish-fail-first", REQUEST))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        assertThat(workerExecutor.pendingCount()).isZero();
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) { assertThat(switchLease).isNotNull(); }

        MeetingSession failedBeforeRetry = sessionStore.find(SESSION_ID).orElseThrow();
        MeetingSessionStore.PublishSnapshot failureBeforeRetry = sessionStore.findPublishSnapshot(SESSION_ID);
        workerExecutor.rejectNext();
        mockMvc.perform(publish(SESSION_ID, "9", "publish-retry-rejected", REQUEST))
                .andExpect(status().isInternalServerError());
        assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(failedBeforeRetry);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_FAILED");
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(9);
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID)).isEqualTo(failureBeforeRetry);
        assertThat(sessionStore.findPublishSnapshot(SESSION_ID).errorCode()).isEqualTo("DOCUMENT_FAILURE");
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) { assertThat(switchLease).isNotNull(); }

        when(providerFactory.create(any())).thenReturn(new FakePublishProvider(false));
        mockMvc.perform(publish(SESSION_ID, "9", "publish-retry", REQUEST)).andExpect(status().isAccepted());
        workerExecutor.drain();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("DOCUMENT_SAVED");
    }

    @Test
    void rejectedQueueRestoresSnapshotAndReleasesLease() throws Exception {
        MeetingSession before = sessionStore.find(SESSION_ID).orElseThrow();
        workerExecutor.rejectNext();
        mockMvc.perform(publish(SESSION_ID, "7", "publish-rejected", REQUEST)).andExpect(status().isInternalServerError());
        assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(before);
        assertThat(workerExecutor.pendingCount()).isZero();
        try (var switchLease = gate.beginSwitch(globalSettingsStore.read().version())) { assertThat(switchLease).isNotNull(); }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder publish(
            String sessionId, String version, String key, String body) {
        return post(PUBLISH_URL, sessionId).header("If-Match", "\"" + version + "\"")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestFixtures {
        @Bean @Primary
        GlobalSettingsStore activeSettings() {
            return DocumentSettingsFixtures.activeNotion("fake-token", "fake-root");
        }

        @Bean @Primary @Qualifier("processingJobExecutor")
        ManualPublishExecutor manualPublishExecutor() {
            return new ManualPublishExecutor();
        }
    }

    static final class ManualPublishExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
        private boolean reject;
        @Override public void execute(Runnable command) { if (reject) { reject = false; throw new IllegalStateException("queue rejected"); } queued.add(command); }
        int pendingCount() { return queued.size(); }
        void reset() { queued.clear(); reject = false; }
        void rejectNext() { reject = true; }
        void drain() { Runnable task; while ((task = queued.poll()) != null) task.run(); }
    }

    static final class FakePublishProvider implements DocumentStructureProvider, DocumentTransferPort {
        private static final java.util.concurrent.atomic.AtomicInteger PUBLISH_CALLS = new java.util.concurrent.atomic.AtomicInteger();
        private final boolean fail;
        FakePublishProvider() { this(false); }
        FakePublishProvider(boolean fail) { this.fail = fail; }
        @Override public ProviderHealth validateConnection() { return null; }
        @Override public DocumentStructure discoverStructure(String rootId) { return null; }
        @Override public TransferManifest readManifest() { return new TransferManifest("sha256:" + "0".repeat(64), List.of(), List.of()); }
        @Override public TransferRecord exportMeeting(String sourceDocumentId) { return null; }
        @Override public Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint) { return null; }
        @Override public MeetingDocumentRef importMeeting(TransferRecord record, java.util.Map<String, String> map,
                String sourceConnectionFingerprint) { return null; }
        @Override public MeetingDocumentRef publishMeeting(TransferRecord record) {
            PUBLISH_CALLS.incrementAndGet();
            if (fail) throw new IllegalStateException("provider failure");
            assertThat(record.marker().meetingAt().toString()).isEqualTo("2026-10-01T12:00+09:00");
            assertThat(new TransferCodec().canonicalDigest(record)).isNotBlank();
            return new MeetingDocumentRef("doc_publish", "https://docs.example/doc_publish");
        }
    }
}
