package com.meetingautomation;

import com.meetingautomation.document.settings.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;

public final class DocumentSettingsFixtures {
    public static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private DocumentSettingsFixtures() { }
    public static GlobalSettingsStore activeNotion(String token, String root) {
        var store = empty();
        var active = new GlobalDocumentSettings.Connection("connection-fixture", 1, "NOTION",
            Map.of("parentPageId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), Map.of("token", token), root,
            "meetings-page-id", "participants-page-id", Instant.now());
        store.transaction(current -> new GlobalSettingsStore.Change<>(
            new GlobalDocumentSettings(1, 1, active, null, null, Map.of()), null));
        return store;
    }
    public static GlobalSettingsStore empty() {
        try { return new EncryptedFileGlobalSettingsStore(Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".active-settings-fixture-").toString(), KEY, false); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
