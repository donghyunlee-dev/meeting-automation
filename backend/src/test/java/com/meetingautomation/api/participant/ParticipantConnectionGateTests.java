package com.meetingautomation.api.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.meetingautomation.api.idempotency.ParticipantCreationCoordinator;
import com.meetingautomation.document.CreateParticipantCommand;
import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantCreationProvider;
import com.meetingautomation.document.ProviderHealth;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import com.meetingautomation.document.settings.SettingsException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ParticipantConnectionGateTests {
    @Test
    void participantProviderWriteHoldsSwitchGateUntilExternalCreateReturns() throws Exception {
        var connection = new GlobalDocumentSettings.Connection("connection", 4, "NOTION", Map.of(), Map.of(),
                "root", "meetings", "participants", Instant.parse("2026-10-12T00:00:00Z"));
        var store = new InMemoryStore(new GlobalDocumentSettings(1, 8, connection, null, null, Map.of()));
        var gate = new DocumentConnectionGate(store);
        var enteredProvider = new CountDownLatch(1);
        var releaseProvider = new CountDownLatch(1);
        ParticipantCreationProvider provider = new ParticipantCreationProvider() {
            @Override public ProviderHealth validateConnection() { return new ProviderHealth(true, true, true); }
            @Override public DocumentStructure discoverStructure(String rootId) {
                return new DocumentStructure(rootId, "meetings", "participants");
            }
            @Override public java.util.List<Participant> listParticipants(String pageId) { return java.util.List.of(); }
            @Override public Participant createParticipant(String pageId, CreateParticipantCommand command) {
                enteredProvider.countDown();
                await(releaseProvider);
                return new Participant("participant-1", command.name(), command.email());
            }
        };
        var resolver = mock(DocumentProviderResolver.class);
        when(resolver.active()).thenReturn(Optional.of(connection));
        when(resolver.selectedProvider()).thenReturn(Optional.of((DocumentStructureProvider) provider));
        when(resolver.configured()).thenReturn(true);
        when(resolver.rootId()).thenReturn("root");
        var service = new ParticipantService(resolver, new ParticipantCreationCoordinator(), gate);

        try (var worker = Executors.newSingleThreadExecutor()) {
            var creation = worker.submit(() -> service.createParticipant("create-1",
                    new ParticipantCreateRequest("Ada", "ada@example.test")));
            assertThat(enteredProvider.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> gate.beginSwitch(8))
                    .isInstanceOf(SettingsException.class)
                    .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
            releaseProvider.countDown();
            assertThat(creation.get(3, TimeUnit.SECONDS)).isEqualTo(new Participant("participant-1", "Ada", "ada@example.test"));
        }

        try (var switchLease = gate.beginSwitch(8)) {
            assertThatThrownBy(() -> gate.beginWrite(4))
                    .isInstanceOf(SettingsException.class)
                    .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Synthetic provider was not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
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
        @Override public String fingerprint(String value) { return value; }
    }
}
