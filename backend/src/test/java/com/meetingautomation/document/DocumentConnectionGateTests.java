package com.meetingautomation.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import com.meetingautomation.document.settings.SettingsException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DocumentConnectionGateTests {
    @Test
    void switchWaitsForParticipantWriteLeaseAndNewWritesSeeBusyUntilSwitchCloses() {
        var store = new InMemorySettingsStore(activeSettings(4));
        var gate = new DocumentConnectionGate(store);
        var write = gate.beginWrite(4);

        assertThatThrownBy(() -> gate.beginSwitch(12))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");

        write.close();
        var switching = gate.beginSwitch(12);
        assertThatThrownBy(() -> gate.beginWrite(4))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        switching.close();

        try (var nextWrite = gate.beginWrite(4)) {
            assertThatThrownBy(() -> gate.beginSwitch(12))
                    .isInstanceOf(SettingsException.class)
                    .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        }
    }

    @Test
    void sessionCreationAndSwitchHaveOneWinnerAtTheSharedLockBoundary() throws Exception {
        var gate = new DocumentConnectionGate(new InMemorySettingsStore(activeSettings(7)));
        var start = new CountDownLatch(1);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var session = executor.submit(() -> {
                await(start);
                try (var lease = gate.beginSession(7, "session-1")) {
                    acquired.countDown();
                    await(release);
                    return "ACQUIRED";
                }
                catch (SettingsException failure) { return failure.code(); }
            });
            var switching = executor.submit(() -> {
                await(start);
                try (var lease = gate.beginSwitch(12)) {
                    acquired.countDown();
                    await(release);
                    return "ACQUIRED";
                }
                catch (SettingsException failure) { return failure.code(); }
            });
            start.countDown();

            assertThat(acquired.await(3, TimeUnit.SECONDS)).isTrue();
            long loserDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!session.isDone() && !switching.isDone() && System.nanoTime() < loserDeadline) Thread.onSpinWait();
            assertThat(session.isDone() ^ switching.isDone()).isTrue();
            var losingFuture = session.isDone() ? session : switching;
            assertThat(losingFuture.get(3, TimeUnit.SECONDS)).isEqualTo("DOCUMENT_CHANGE_BUSY");
            release.countDown();
            var outcomes = java.util.List.of(session.get(3, TimeUnit.SECONDS), switching.get(3, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("ACQUIRED", "DOCUMENT_CHANGE_BUSY");
        }
    }

    @Test
    void sessionCreationRejectsStaleActiveConnectionVersion() {
        var gate = new DocumentConnectionGate(new InMemorySettingsStore(activeSettings(9)));

        assertThatThrownBy(() -> gate.beginSession(8, "session-stale"))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CONNECTION_CHANGED");
    }

    @Test
    void duplicateLiveSessionIdsCannotAcquireAnotherSessionLease() {
        var gate = new DocumentConnectionGate(new InMemorySettingsStore(activeSettings(2)));
        var first = gate.beginSession(2, "same-session");

        assertThatThrownBy(() -> gate.beginSession(2, "same-session"))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");

        first.close();
        try (var second = gate.beginSession(2, "same-session")) {
            assertThat(second).isNotNull();
        }
    }

    @Test
    void durableInterruptedOperationKeepsSessionsAndWritesBlockedAfterProcessLeaseIsGone() {
        var initial = activeSettings(3);
        var interrupted = new GlobalDocumentSettings.Operation("operation-1", "SWITCH", "INTERRUPTED");
        var store = new InMemorySettingsStore(new GlobalDocumentSettings(1, 13, initial.active(), null,
                interrupted, Map.of()));
        var gate = new DocumentConnectionGate(store);

        assertThatThrownBy(() -> gate.beginSession(3, "session-after-restart"))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        assertThatThrownBy(() -> gate.beginWrite(3))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        assertThatThrownBy(() -> gate.beginSwitch(13))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
    }

    @Test
    void staleSettingsVersionCannotAcquireSwitchLease() {
        var gate = new DocumentConnectionGate(new InMemorySettingsStore(activeSettings(3)));

        assertThatThrownBy(() -> gate.beginSwitch(11))
                .isInstanceOf(SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_SETTINGS_VERSION_CONFLICT");
    }

    private static GlobalDocumentSettings activeSettings(long connectionVersion) {
        var active = new GlobalDocumentSettings.Connection("connection", connectionVersion, "NOTION",
                Map.of("parentPageId", "parent"), Map.of("token", "synthetic-token"),
                "root", "meetings", "participants", Instant.parse("2026-10-12T00:00:00Z"));
        return new GlobalDocumentSettings(1, 12, active, null, null, Map.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrency latch timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class InMemorySettingsStore implements GlobalSettingsStore {
        private GlobalDocumentSettings settings;
        private InMemorySettingsStore(GlobalDocumentSettings settings) { this.settings = settings; }
        @Override public synchronized GlobalDocumentSettings read() { return settings; }
        @Override public synchronized <T> T transaction(java.util.function.Function<GlobalDocumentSettings, Change<T>> mutation) {
            Change<T> change = mutation.apply(settings);
            settings = change.snapshot();
            return change.result();
        }
        @Override public String fingerprint(String normalizedRequest) { return normalizedRequest; }
    }
}
