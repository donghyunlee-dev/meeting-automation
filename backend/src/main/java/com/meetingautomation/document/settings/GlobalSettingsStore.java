package com.meetingautomation.document.settings;

import java.util.function.Function;

/** One lock and one snapshot are shared by setup and future bootstrap/migration use cases. */
public interface GlobalSettingsStore {
    GlobalDocumentSettings read();
    <T> T transaction(Function<GlobalDocumentSettings, Change<T>> mutation);
    String fingerprint(String normalizedRequest);
    record Change<T>(GlobalDocumentSettings snapshot, T result) { }
}
