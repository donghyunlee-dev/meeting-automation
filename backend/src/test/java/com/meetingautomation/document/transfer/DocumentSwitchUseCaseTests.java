package com.meetingautomation.document.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import com.meetingautomation.document.settings.SettingsException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DocumentSwitchUseCaseTests {
    @Test
    void startEmptyKeepsSourceUntilVerifiedTargetIsAtomicallyActivated() {
        var fixture = new Fixture();

        var accepted = fixture.useCase.start(10, "switch-empty", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true));
        assertThat(accepted.status()).isEqualTo(202);
        assertThat(fixture.store.read().active().rootId()).isEqualTo("source-root");
        assertThat(fixture.store.read().operation().status()).isEqualTo("PENDING");

        fixture.executor.runNext();

        var completed = fixture.store.read();
        assertThat(completed.active().rootId()).isEqualTo("target-root");
        assertThat(completed.active().connectionVersion()).isEqualTo(2);
        assertThat(completed.active().credentials()).containsEntry("apiToken", "target-token");
        assertThat(completed.draft()).isNull();
        assertThat(completed.operation().status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.source.reads).isZero();
        assertThat(fixture.target.reads).isEqualTo(1);
    }

    @Test
    void startRejectsAnInFlightSessionAndStaleSettingsVersion() {
        var fixture = new Fixture();
        var session = fixture.gate.beginSession(1, "meeting-session");

        assertThatThrownBy(() -> fixture.useCase.start(10, "busy", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true)))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        session.close();

        assertThatThrownBy(() -> fixture.useCase.start(9, "stale", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true)))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_SETTINGS_VERSION_CONFLICT");
        assertThat(fixture.executor.size()).isZero();
    }

    @Test
    void startReplaysIdenticalIdempotencyKeyAndRejectsDifferentInput() {
        var fixture = new Fixture();
        var request = new DocumentSwitchUseCase.Request("draft-target", 1, "START_EMPTY", true);
        var first = fixture.useCase.start(10, "switch-once", request);

        assertThat(fixture.useCase.start(0, "switch-once", request)).isEqualTo(first);
        assertThatThrownBy(() -> fixture.useCase.start(10, "switch-once",
                new DocumentSwitchUseCase.Request("draft-target", 1, "COPY_ALL", true)))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(fixture.executor.size()).isEqualTo(1);
    }

    @Test
    void startingANewSwitchPurgesTerminalOperationHistoryOlderThanThirtyDays() {
        var fixture = new Fixture();
        var expired = new GlobalDocumentSettings.Operation("expired-operation", "SWITCH", "SUCCEEDED",
                "old-draft", 1, "ACTIVATE", Map.of(), null, false,
                Instant.parse("2026-09-11T23:59:59Z"));
        fixture.store.transaction(current -> new GlobalSettingsStore.Change<>(new GlobalDocumentSettings(1,
                current.version(), current.active(), current.draft(), current.operation(),
                current.idempotencyRecords(), Map.of(expired.operationId(), expired)), null));

        assertThatThrownBy(() -> fixture.useCase.operation("expired-operation"))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_OPERATION_NOT_FOUND");

        fixture.useCase.start(10, "switch-after-history-expiry", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true));

        assertThat(fixture.store.read().operationHistory()).doesNotContainKey("expired-operation");
    }

    @Test
    void cancellingQueuedSwitchKeepsOriginalConnectionAndPreventsWorkerActivation() {
        var fixture = new Fixture();
        var accepted = fixture.useCase.start(10, "switch-empty", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true));

        fixture.useCase.cancel(accepted.version(), "cancel-empty", accepted.operationId());
        fixture.executor.runNext();

        assertThat(fixture.store.read().active().rootId()).isEqualTo("source-root");
        assertThat(fixture.store.read().operation().status()).isEqualTo("CANCELLED");
        assertThat(fixture.store.read().draft()).isNull();
    }

    @Test
    void cancellationDuringProviderReadWaitsForTheRequestThenKeepsSourceActive() throws Exception {
        var fixture = new Fixture();
        fixture.target.blockReads = true;
        var accepted = fixture.useCase.start(10, "switch-empty", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true));
        try (var worker = Executors.newSingleThreadExecutor()) {
            var running = worker.submit(fixture.executor::runNext);
            assertThat(fixture.target.readEntered.await(3, TimeUnit.SECONDS)).isTrue();

            var cancelling = fixture.useCase.cancel(fixture.store.read().version(), "cancel-running", accepted.operationId());
            assertThat(cancelling.status()).isEqualTo(202);
            assertThat(fixture.useCase.operation(accepted.operationId()).cancelRequested()).isTrue();
            fixture.target.releaseRead.countDown();
            running.get(3, TimeUnit.SECONDS);
        }

        assertThat(fixture.store.read().active().rootId()).isEqualTo("source-root");
        assertThat(fixture.store.read().operation().status()).isEqualTo("CANCELLED");
        assertThat(fixture.store.read().draft()).isNull();
    }

    @Test
    void interruptedSwitchBlocksNewWritesUntilExplicitRetryCompletes() {
        var fixture = new Fixture();
        var accepted = fixture.useCase.start(10, "switch-empty", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true));
        fixture.store.transaction(current -> {
            var interrupted = current.operation().transition("INTERRUPTED", "VERIFY", current.operation().journal(),
                    "DOCUMENT_FAILED", true, Instant.parse("2026-10-12T00:01:00Z"));
            return new GlobalSettingsStore.Change<>(new GlobalDocumentSettings(1, current.version() + 1,
                    current.active(), current.draft(), interrupted, current.idempotencyRecords()), null);
        });

        assertThatThrownBy(() -> fixture.gate.beginWrite(1))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        var retry = fixture.useCase.retry(fixture.store.read().version(), "retry-switch", accepted.operationId());
        fixture.executor.runNext();

        assertThat(retry.status()).isEqualTo(202);
        assertThat(fixture.store.read().operation().status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.store.read().active().rootId()).isEqualTo("target-root");
        assertThat(fixture.target.reads).isEqualTo(1);
    }

    @Test
    void cancellingInterruptedSwitchReleasesDurableLockWithoutChangingSource() {
        var fixture = new Fixture();
        var accepted = fixture.useCase.start(10, "switch-empty", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true));
        fixture.store.transaction(current -> {
            var interrupted = current.operation().transition("INTERRUPTED", "VERIFY", current.operation().journal(),
                    "DOCUMENT_FAILED", true, Instant.parse("2026-10-12T00:01:00Z"));
            return new GlobalSettingsStore.Change<>(new GlobalDocumentSettings(1, current.version() + 1,
                    current.active(), current.draft(), interrupted, current.idempotencyRecords()), null);
        });

        fixture.useCase.cancel(fixture.store.read().version(), "cancel-interrupted", accepted.operationId());

        assertThat(fixture.store.read().operation().status()).isEqualTo("CANCELLED");
        assertThat(fixture.store.read().active().rootId()).isEqualTo("source-root");
        assertThat(fixture.store.read().draft()).isNull();
        try (var write = fixture.gate.beginWrite(1)) {
            assertThatThrownBy(() -> fixture.gate.beginSwitch(fixture.store.read().version()))
                    .isInstanceOf(SettingsException.class)
                    .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        }
    }

    @Test
    void startEmptyRefusesTargetWithExistingParticipantData() {
        var fixture = new Fixture();
        fixture.target.contentPresent = true;
        fixture.useCase.start(10, "switch-empty", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "START_EMPTY", true));

        fixture.executor.runNext();

        assertThat(fixture.store.read().active().rootId()).isEqualTo("source-root");
        assertThat(fixture.store.read().draft().draftId()).isEqualTo("draft-target");
        assertThat(fixture.store.read().operation().status()).isEqualTo("FAILED");
        assertThat(fixture.store.read().operation().errorCode()).isEqualTo("DOCUMENT_MIGRATION_CONFLICT");
    }

    @Test
    void copyAllVerifiesTransferAndPersistsOnlyProgressCountsAndIdMapBeforeActivation() {
        var fixture = new Fixture();
        fixture.source.fingerprint = "sha256:" + "1".repeat(64);
        fixture.target.fingerprint = "sha256:" + "2".repeat(64);
        fixture.source.seedParticipant("source-person", "Ada", "ada@example.test");
        fixture.useCase.start(10, "switch-copy", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "COPY_ALL", true));

        fixture.executor.runNext();

        var result = fixture.store.read();
        assertThat(result.active().rootId()).isEqualTo("target-root");
        assertThat(result.active().credentials()).containsEntry("apiToken", "target-token");
        assertThat(result.operation().status()).isEqualTo("SUCCEEDED");
        assertThat(result.operation().participantsTotal()).isEqualTo(1);
        assertThat(result.operation().participantsCopied()).isEqualTo(1);
        assertThat(result.operation().participantIdMap()).containsEntry("source-person", "target-person");
        assertThat(result.operation().toString()).doesNotContain("ada@example.test", "Ada");
        assertThat(fixture.target.participants).hasSize(1);
    }

    @Test
    void sourceEditPreventsActivationAndExplicitRetryReusesTheCompletedTargetParticipant() {
        var fixture = new Fixture();
        fixture.source.fingerprint = "sha256:" + "1".repeat(64);
        fixture.target.fingerprint = "sha256:" + "2".repeat(64);
        fixture.source.seedParticipant("source-person", "Ada", "ada@example.test");
        fixture.source.changeSourceOnSecondRead = true;
        var accepted = fixture.useCase.start(10, "switch-copy", new DocumentSwitchUseCase.Request(
                "draft-target", 1, "COPY_ALL", true));

        fixture.executor.runNext();

        assertThat(fixture.store.read().operation().status()).isEqualTo("FAILED");
        assertThat(fixture.store.read().operation().errorCode()).isEqualTo("DOCUMENT_MIGRATION_SOURCE_CHANGED");
        assertThat(fixture.store.read().operation().retryable()).isTrue();
        assertThat(fixture.store.read().active().rootId()).isEqualTo("source-root");
        assertThat(fixture.target.participantImports).isEqualTo(1);

        fixture.source.participants.clear();
        fixture.source.seedParticipant("source-person", "Ada", "ada@example.test");
        var retry = fixture.useCase.retry(fixture.store.read().version(), "retry-copy", accepted.operationId());
        fixture.executor.runNext();

        assertThat(retry.status()).isEqualTo(202);
        assertThat(fixture.store.read().operation().status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.store.read().active().rootId()).isEqualTo("target-root");
        assertThat(fixture.store.read().operation().participantsCopied()).isZero();
        assertThat(fixture.store.read().operation().participantIdMap()).containsEntry("source-person", "target-person");
        assertThat(fixture.target.participantImports).isEqualTo(1);
    }

    private static final class Fixture {
        private final InMemoryStore store = new InMemoryStore(initialSettings());
        private final DocumentConnectionGate gate = new DocumentConnectionGate(store);
        private final QueueExecutor executor = new QueueExecutor();
        private final StubPort source = new StubPort("source-fingerprint");
        private final StubPort target = new StubPort("target-fingerprint");
        private final DocumentSwitchUseCase useCase = new DocumentSwitchUseCase(store, gate, connection ->
                connection.rootId().equals("source-root") ? this.source : this.target,
                new DocumentTransferService(), executor, Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC));
    }

    private static GlobalDocumentSettings initialSettings() {
        var active = new GlobalDocumentSettings.Connection("source-connection", 1, "NOTION",
                Map.of("parentPageId", "source-parent"), Map.of("token", "source-token"), "source-root",
                "source-meetings", "source-participants", Instant.parse("2026-10-01T00:00:00Z"));
        var test = new GlobalDocumentSettings.TestResult(Instant.parse("2026-10-12T00:00:00Z"),
                Instant.parse("2026-10-12T00:10:00Z"), true, true, "UNVERIFIED");
        var draft = new GlobalDocumentSettings.Draft("draft-target", 1, "CONFLUENCE",
                Map.of("baseUrl", "https://example.atlassian.net", "spaceId", "1"),
                Map.of("accountEmail", "test@example.test", "apiToken", "target-token"), null,
                "PREPARED", test, Instant.parse("2026-10-13T00:00:00Z"),
                new DocumentStructure("target-root", "target-meetings", "target-participants"));
        return new GlobalDocumentSettings(1, 10, active, draft, null, Map.of());
    }

    private static final class InMemoryStore implements GlobalSettingsStore {
        private GlobalDocumentSettings settings;
        private InMemoryStore(GlobalDocumentSettings settings) { this.settings = settings; }
        @Override public synchronized GlobalDocumentSettings read() { return settings; }
        @Override public synchronized <T> T transaction(java.util.function.Function<GlobalDocumentSettings, Change<T>> mutation) {
            Change<T> change = mutation.apply(settings);
            settings = change.snapshot();
            return change.result();
        }
        @Override public String fingerprint(String request) { return "hmac:" + request; }
    }

    private static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable task) { tasks.add(task); }
        synchronized int size() { return tasks.size(); }
        void runNext() { Runnable task; synchronized (this) { task = tasks.remove(); } task.run(); }
    }

    private static final class StubPort implements DocumentTransferPort {
        private String fingerprint;
        private int reads;
        private boolean blockReads;
        private boolean contentPresent;
        private boolean changeSourceOnSecondRead;
        private int participantImports;
        private final java.util.List<TransferRecord> participants = new java.util.ArrayList<>();
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private StubPort(String fingerprint) { this.fingerprint = fingerprint; }
        @Override public TransferManifest readManifest() {
            reads++;
            if (blockReads) {
                readEntered.countDown();
                try {
                    if (!releaseRead.await(10, TimeUnit.SECONDS)) throw new AssertionError("Provider read was not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            if (changeSourceOnSecondRead && reads == 2 && !participants.isEmpty()) {
                TransferRecord original = participants.getFirst();
                participants.set(0, new TransferRecord(original.sourceDocumentId(), "edited-revision", null,
                        original.marker(), original.title(), original.minutes(), original.transcript(),
                        original.participants(), original.participant()));
            }
            List<TransferRecord> records = contentPresent
                    ? List.of(new TransferRecord("target-person", "revision-1", null,
                            new TransferMarker("meeting-automation", "participant", "1.0", null, null, null,
                                    List.of(), List.of(), null, "sha256:" + "0".repeat(64), null),
                            "Synthetic Participant", null, null, List.of(),
                            new Participant("target-person", "Synthetic Participant", "synthetic@example.test")))
                    : List.copyOf(participants);
            return new TransferManifest(fingerprint, records, List.of());
        }
        @Override public TransferRecord exportMeeting(String id) { throw new AssertionError("Empty target has no meetings"); }
        @Override public MeetingDocumentRef publishMeeting(TransferRecord meeting) { throw new UnsupportedOperationException(); }
        @Override public Participant importParticipant(TransferRecord record, String sourceFingerprint) {
            participantImports++;
            String id = "target-person";
            var codec = new TransferCodec();
            var transfer = new TransferMarker.Transfer(codec.transferKey(sourceFingerprint, record.sourceDocumentId()),
                    sourceFingerprint, record.sourceDocumentId(), record.sourceRevision(), codec.canonicalDigest(record), Map.of());
            var old = record.marker();
            var marker = new TransferMarker(old.format(), old.recordType(), old.schemaVersion(), null, null, null,
                    List.of(), List.of(), null, codec.canonicalDigest(record), transfer);
            var participant = new Participant(id, record.participant().name(), record.participant().email());
            participants.add(new TransferRecord(id, "target-revision", null, marker, record.title(), null, null,
                    List.of(), participant));
            return participant;
        }
        @Override public MeetingDocumentRef importMeeting(TransferRecord record, Map<String, String> map, String sourceFingerprint) { throw new AssertionError("No meetings to import"); }

        private void seedParticipant(String id, String name, String email) {
            var codec = new TransferCodec();
            var marker = new TransferMarker("meeting-automation", "participant", "1.0", null, null, null,
                    List.of(), List.of(), null, "sha256:" + "0".repeat(64), null);
            var unmarked = new TransferRecord(id, "source-revision", null, marker, name, null, null, List.of(),
                    new Participant(id, name, email));
            marker = new TransferMarker(marker.format(), marker.recordType(), marker.schemaVersion(), null, null, null,
                    List.of(), List.of(), null, codec.canonicalDigest(unmarked), null);
            participants.add(new TransferRecord(id, "source-revision", null, marker, name, null, null, List.of(),
                    unmarked.participant()));
        }
    }
}
