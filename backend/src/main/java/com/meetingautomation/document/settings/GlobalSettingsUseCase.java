package com.meetingautomation.document.settings;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class GlobalSettingsUseCase {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final GlobalSettingsStore store;
    private final DocumentConnectionTester tester;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public GlobalSettingsUseCase(GlobalSettingsStore store, DocumentConnectionTester tester) {
        this(store, tester, Clock.systemUTC());
    }
    public GlobalSettingsUseCase(GlobalSettingsStore store, DocumentConnectionTester tester, Clock clock) {
        this.store = store; this.tester = tester; this.clock = clock;
    }
    public Status status() {
        try {
            GlobalDocumentSettings state = store.transaction(current -> {
                var draft = current.draft();
                if (draft != null && !locked(current) && !draft.expiresAt().isAfter(clock.instant())) {
                    var cleaned = new GlobalDocumentSettings(1, current.version() + 1, current.active(), null,
                            current.operation(), current.idempotencyRecords());
                    return new GlobalSettingsStore.Change<>(cleaned, cleaned);
                }
                return new GlobalSettingsStore.Change<>(current, current);
            });
            var active = state.active();
            var draft = liveDraft(state);
            boolean locked = locked(state);
            return new Status(state.version(), locked ? active == null ? "CONFIGURING" : "SWITCHING"
                : active != null ? "READY" : draft != null ? "CONFIGURING" : "UNCONFIGURED",
                active == null ? null : new ActiveSummary(active.provider(), active.connectionVersion(), active.rootUrl()),
                draft == null ? null : new DraftSummary(draft.draftId(), draft.revision(), draft.provider(), draft.state()),
                state.operation(), active != null && !locked);
        } catch (SettingsException failure) {
            if (failure.status() != 503) throw failure;
            return new Status(null, "STORAGE_UNAVAILABLE", null, null, null, false);
        }
    }
    public MutationResult saveDraft(long version, String key, DocumentDraftInput input) {
        DocumentDraftInput normalized = input.normalized();
        String hmac = store.fingerprint("draft:" + JSON.writeValueAsString(normalized));
        return store.transaction(current -> {
            var replay = replay(current, key, hmac);
            if (replay != null) return new GlobalSettingsStore.Change<>(current, replay);
            checkVersion(current, version);
            if (locked(current)) throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            var previous = liveDraft(current);
            var draft = new GlobalDocumentSettings.Draft(previous == null ? "draft_" + UUID.randomUUID() : previous.draftId(),
                previous == null ? 1 : previous.revision() + 1, normalized.provider(), normalized.location(),
                normalized.credentials(), normalized.reuseExistingRootId(), "DRAFT", null, clock.instant().plus(Duration.ofHours(24)));
            Map<String, Object> response = Map.of("draftId", draft.draftId(), "revision", draft.revision(), "state", "DRAFT", "version", current.version() + 1);
            return changed(current, draft, key, hmac, 201, response);
        });
    }
    public MutationResult testDraft(long version, String key, String draftId, long revision) {
        String hmac = store.fingerprint("test:" + draftId + ":" + revision);
        TestPreparation preparation = store.transaction(current -> {
            var replay = replay(current, key, hmac);
            if (replay != null) return new GlobalSettingsStore.Change<>(current, new TestPreparation(null, replay));
            checkVersion(current, version);
            if (locked(current)) throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            var draft = liveDraft(current);
            if (draft == null || !draft.draftId().equals(draftId)) throw new SettingsException("DOCUMENT_DRAFT_NOT_FOUND", 404);
            if (draft.revision() != revision) throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
            var snapshot = new GlobalDocumentSettings.Draft(draft.draftId(), draft.revision(), draft.provider(),
                Map.copyOf(draft.location()), Map.copyOf(draft.credentials()), draft.reuseExistingRootId(),
                draft.state(), draft.testResult(), draft.expiresAt());
            return new GlobalSettingsStore.Change<>(current, new TestPreparation(snapshot, null));
        });
        if (preparation.replay() != null) return preparation.replay();

        // Provider I/O must never hold the process-wide snapshot lock.
        tester.verify(preparation.draft());

        return store.transaction(current -> {
            var replay = replay(current, key, hmac);
            if (replay != null) return new GlobalSettingsStore.Change<>(current, replay);
            checkVersion(current, version);
            if (locked(current)) throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            var draft = liveDraft(current);
            if (draft == null || !draft.draftId().equals(draftId) || draft.revision() != revision)
                throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
            Instant now = clock.instant();
            var test = new GlobalDocumentSettings.TestResult(now, now.plus(Duration.ofMinutes(10)), true, true, "UNVERIFIED");
            var tested = new GlobalDocumentSettings.Draft(draft.draftId(), draft.revision(), draft.provider(), draft.location(),
                draft.credentials(), draft.reuseExistingRootId(), "TESTED", test, draft.expiresAt());
            Map<String, Object> response = Map.of("draftId", draftId, "revision", revision, "testedAt", now.toString(),
                "expiresAt", test.expiresAt().toString(), "authenticated", true, "parentAccessible", true,
                "writeCapability", "UNVERIFIED", "version", current.version() + 1);
            return changed(current, tested, key, hmac, 200, response);
        });
    }
    private record TestPreparation(GlobalDocumentSettings.Draft draft, MutationResult replay) { }
    private GlobalDocumentSettings.Draft liveDraft(GlobalDocumentSettings state) {
        var draft = state.draft();
        return draft != null && (draft.expiresAt().isAfter(clock.instant()) || locked(state)) ? draft : null;
    }
    private static boolean locked(GlobalDocumentSettings state) {
        return state.operation() != null && !Set.of("SUCCEEDED", "CANCELLED").contains(state.operation().status());
    }
    private static void checkVersion(GlobalDocumentSettings current, long requested) {
        if (current.version() != requested) throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
    }
    private static MutationResult replay(GlobalDocumentSettings current, String key, String hmac) {
        var record = current.idempotencyRecords().get(key);
        if (record == null) return null;
        if (!record.requestHmac().equals(hmac)) throw new SettingsException("IDEMPOTENCY_KEY_CONFLICT", 409);
        return new MutationResult(record.status(), record.responseJson(), record.version());
    }
    private static GlobalSettingsStore.Change<MutationResult> changed(GlobalDocumentSettings current,
            GlobalDocumentSettings.Draft draft, String key, String hmac, int status, Map<String, Object> response) {
        String json = JSON.writeValueAsString(Map.of("data", response));
        var records = new HashMap<>(current.idempotencyRecords());
        records.put(key, new GlobalDocumentSettings.Replay(hmac, status, json, current.version() + 1));
        var next = new GlobalDocumentSettings(1, current.version() + 1, current.active(), draft, current.operation(), Map.copyOf(records));
        return new GlobalSettingsStore.Change<>(next, new MutationResult(status, json, next.version()));
    }
    public record MutationResult(int status, String responseJson, long version) { }
    public record Status(Long version, String status, ActiveSummary active, DraftSummary draft,
            GlobalDocumentSettings.Operation operation, boolean canCreateMeeting) { }
    public record ActiveSummary(String provider, long connectionVersion, String rootUrl) { }
    public record DraftSummary(String draftId, long revision, String provider, String state) { }
}
