package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.*;

import com.meetingautomation.document.settings.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryGlobalSettingsStoreTests {
    @Test void startsEmptyAndKeepsCommittedStateForTheProcessLifetime() {
        var store = new InMemoryGlobalSettingsStore();

        assertEquals(0, store.read().version());
        store.transaction(current -> new GlobalSettingsStore.Change<>(
                new GlobalDocumentSettings(1, 1, null, null, null, Map.of()), "saved"));

        assertEquals(1, store.read().version());
    }

    @Test void aNewStoreRepresentsABackendRestartAndStartsEmpty() {
        var running = new InMemoryGlobalSettingsStore();
        running.transaction(current -> new GlobalSettingsStore.Change<>(
                new GlobalDocumentSettings(1, 1, null, null, null, Map.of()), null));

        assertEquals(0, new InMemoryGlobalSettingsStore().read().version());
    }

    @Test void fingerprintsAreStableWithinOneProcessStoreAndDifferentAcrossRestarts() {
        var running = new InMemoryGlobalSettingsStore();

        assertEquals(running.fingerprint("same"), running.fingerprint("same"));
        assertNotEquals(running.fingerprint("same"), running.fingerprint("different"));
        assertNotEquals(running.fingerprint("same"), new InMemoryGlobalSettingsStore().fingerprint("same"));
    }
}
