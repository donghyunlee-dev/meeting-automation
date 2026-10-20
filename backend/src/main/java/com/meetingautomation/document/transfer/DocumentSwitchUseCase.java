package com.meetingautomation.document.transfer;

import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import com.meetingautomation.document.settings.SettingsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Durable coordinator for COPY_ALL and START_EMPTY connection changes. */
@Service
public final class DocumentSwitchUseCase {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "CANCELLED");

    private final GlobalSettingsStore store;
    private final DocumentConnectionGate gate;
    private final DocumentTransferProviderFactory providers;
    private final DocumentTransferService transfer;
    private final Executor executor;
    private final Clock clock;

    @Autowired
    public DocumentSwitchUseCase(GlobalSettingsStore store, DocumentConnectionGate gate,
            DocumentTransferProviderFactory providers, SwitchTaskRunner executor) {
        this(store, gate, providers, new DocumentTransferService(), executor, Clock.systemUTC());
    }

    public DocumentSwitchUseCase(GlobalSettingsStore store, DocumentConnectionGate gate,
            DocumentTransferProviderFactory providers, DocumentTransferService transfer, Executor executor, Clock clock) {
        this.store = store;
        this.gate = gate;
        this.providers = providers;
        this.transfer = transfer;
        this.executor = executor;
        this.clock = clock;
    }

    public MutationResult start(long version, String key, Request request) {
        validate(request, key);
        String normalized = JSON.writeValueAsString(request);
        String hmac = store.fingerprint("switch:" + normalized);
        MutationResult replay = replay(store.read(), key, hmac);
        if (replay != null) return replay;

        try (var ignored = gate.beginSwitch(version)) {
            Acceptance accepted = store.transaction(current -> {
                MutationResult prior = replay(current, key, hmac);
                if (prior != null) return unchanged(current, new Acceptance(prior, null));
                checkVersion(current, version);
                if (current.active() == null) throw new SettingsException("DOCUMENT_SETUP_REQUIRED", 409);
                GlobalDocumentSettings.Draft draft = current.draft();
                if (draft == null || !request.draftId().equals(draft.draftId()))
                    throw new SettingsException("DOCUMENT_DRAFT_NOT_FOUND", 404);
                if (draft.revision() != request.draftRevision())
                    throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
                if (!"PREPARED".equals(draft.state()) || draft.preparedStructure() == null)
                    throw new SettingsException("DOCUMENT_TEST_REQUIRED", 409);
                if (current.operation() != null && !TERMINAL.contains(current.operation().status()))
                    throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
                String operationId = "operation_" + UUID.randomUUID();
                var operation = new GlobalDocumentSettings.Operation(operationId, "SWITCH", "PENDING",
                        draft.draftId(), draft.revision(), "PREFLIGHT",
                        Map.of("MODE", new GlobalDocumentSettings.Creation(null, request.mode(), null, null)),
                        null, false, clock.instant());
                long nextVersion = current.version() + 1;
                MutationResult result = mutation(operationId, "PENDING", nextVersion, 202);
                var records = new HashMap<>(current.idempotencyRecords());
                records.put(key, new GlobalDocumentSettings.Replay(hmac, result.status(), result.responseJson(), nextVersion));
                var snapshot = snapshot(current, current.active(), draft, operation, Map.copyOf(records));
                return new GlobalSettingsStore.Change<>(snapshot, new Acceptance(result, operationId));
            });
            if (accepted.operationId() != null) schedule(accepted.operationId());
            return accepted.result();
        } catch (SettingsException busy) {
            // A concurrent identical request may have committed after the optimistic replay check.
            if ("DOCUMENT_CHANGE_BUSY".equals(busy.code())) {
                MutationResult concurrentReplay = replay(store.read(), key, hmac);
                if (concurrentReplay != null) return concurrentReplay;
            }
            throw busy;
        }
    }

    public MutationResult retry(long version, String key, String operationId) {
        validateKey(key);
        String hmac = store.fingerprint("retry:" + operationId);
        MutationResult replay = replay(store.read(), key, hmac);
        if (replay != null) return replay;
        try (var ignored = gate.resumeSwitch(version, operationId)) {
            Acceptance accepted = store.transaction(current -> {
                MutationResult prior = replay(current, key, hmac);
                if (prior != null) return unchanged(current, new Acceptance(prior, null));
                checkVersion(current, version);
                var operation = require(current, operationId);
                if (!("INTERRUPTED".equals(operation.status()) || "FAILED".equals(operation.status()) && operation.retryable()))
                    throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
                var resumed = operation.resume(clock.instant());
                var records = new HashMap<>(current.idempotencyRecords());
                long nextVersion = current.version() + 1;
                MutationResult result = mutation(operationId, "PENDING", nextVersion, 202);
                records.put(key, new GlobalDocumentSettings.Replay(hmac, result.status(), result.responseJson(), nextVersion));
                return new GlobalSettingsStore.Change<>(snapshot(current, current.active(), current.draft(), resumed,
                        Map.copyOf(records)), new Acceptance(result, operationId));
            });
            if (accepted.operationId() != null) schedule(operationId);
            return accepted.result();
        }
    }

    public MutationResult cancel(long version, String key, String operationId) {
        validateKey(key);
        String hmac = store.fingerprint("cancel:" + operationId);
        MutationResult replay = replay(store.read(), key, hmac);
        if (replay != null) return replay;
        var result = store.transaction(current -> {
            MutationResult prior = replay(current, key, hmac);
            if (prior != null) return new GlobalSettingsStore.Change<>(current, prior);
            checkVersion(current, version);
            var operation = require(current, operationId);
            if (!"SWITCH".equals(operation.type()) || TERMINAL.contains(operation.status()))
                throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            GlobalDocumentSettings.Operation cancelled;
            GlobalDocumentSettings.Draft draft = current.draft();
            if (!"RUNNING".equals(operation.status())) {
                cancelled = operation.transition("CANCELLED", operation.stage(), operation.journal(), null, false, clock.instant());
                draft = null;
            } else {
                cancelled = operation.requestCancel(clock.instant());
            }
            long nextVersion = current.version() + 1;
            MutationResult response = mutation(operationId, cancelled.status(), nextVersion, 202);
            var records = new HashMap<>(current.idempotencyRecords());
            records.put(key, new GlobalDocumentSettings.Replay(hmac, response.status(), response.responseJson(), nextVersion));
            return new GlobalSettingsStore.Change<>(snapshot(current, current.active(), draft, cancelled,
                    Map.copyOf(records)), response);
        });
        return result;
    }

    public OperationSummary operation(String operationId) {
        var current = store.read();
        var operation = current.operation();
        if (operation == null || !operation.operationId().equals(operationId))
            operation = current.operationHistory().get(operationId);
        if (operation == null || !"SWITCH".equals(operation.type()) || TERMINAL.contains(operation.status())
                && !operation.updatedAt().plus(Duration.ofDays(30)).isAfter(clock.instant()))
            throw new SettingsException("DOCUMENT_OPERATION_NOT_FOUND", 404);
        return summary(operation, current.version());
    }

    private void schedule(String operationId) {
        try { executor.execute(() -> run(operationId)); }
        catch (RuntimeException rejected) { fail(operationId, "DOCUMENT_FAILED", "INTERRUPTED", true); }
    }

    private void run(String operationId) {
        try {
            RunContext context = store.transaction(current -> {
                var operation = require(current, operationId);
                if (!"PENDING".equals(operation.status())) return unchanged(current, (RunContext) null);
                var draft = current.draft();
                if (draft == null || !draft.draftId().equals(operation.draftId())
                        || draft.revision() != operation.draftRevision() || draft.preparedStructure() == null)
                    throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
                if (current.active() == null) throw new SettingsException("DOCUMENT_SETUP_REQUIRED", 409);
                var running = operation.transition("RUNNING", "PREFLIGHT", operation.journal(), null, false, clock.instant());
                return new GlobalSettingsStore.Change<>(snapshot(current, current.active(), draft, running,
                        current.idempotencyRecords()), new RunContext(current.active(), draft));
            });
            if (context == null) return;
            GlobalDocumentSettings.Connection targetConnection = targetConnection(context.active(), context.draft());
            DocumentTransferPort target = providers.create(targetConnection);
            String mode = mode(operationId);
            if ("START_EMPTY".equals(mode)) {
                TransferManifest targetManifest = target.readManifest();
                if (!targetManifest.participants().isEmpty() || !targetManifest.meetings().isEmpty())
                    throw new TransferConflictException(TransferConflictException.Code.INVALID_TARGET);
                update(operationId, current -> current.progress("VERIFY", 0, 0, 0, 0, Map.of(), clock.instant()));
            } else {
                DocumentTransferPort source = providers.create(context.active());
                transfer.transfer(source, target, progress -> {
                    update(operationId, current -> current.progress(progress.stage(), progress.participantsTotal(),
                            progress.participantsCopied(), progress.meetingsTotal(), progress.meetingsCopied(),
                            progress.participantIdMap(), clock.instant()));
                    if (store.read().operation().cancelRequested()) throw new TransferCancelledException();
                });
            }
            finish(operationId, context.active(), context.draft(), targetConnection);
        } catch (TransferCancelledException cancelled) {
            cancelAtSafePoint(operationId);
        } catch (TransferConflictException conflict) {
            if (conflict.code() == TransferConflictException.Code.RECONCILIATION_REQUIRED)
                fail(operationId, "DOCUMENT_RECONCILIATION_REQUIRED", "RECONCILIATION_REQUIRED", false);
            else if (conflict.code() == TransferConflictException.Code.SOURCE_CHANGED)
                fail(operationId, "DOCUMENT_MIGRATION_SOURCE_CHANGED", "FAILED", true);
            else fail(operationId, "DOCUMENT_MIGRATION_CONFLICT", "FAILED", false);
        } catch (SettingsException failure) {
            fail(operationId, failure.code(), "FAILED", failure.status() >= 500);
        } catch (RuntimeException failure) {
            fail(operationId, "DOCUMENT_MIGRATION_FAILED", "FAILED", false);
        }
    }

    private String mode(String operationId) {
        var operation = require(store.read(), operationId);
        var mode = operation.journal().get("MODE");
        if (mode == null || mode.title() == null) throw new SettingsException("DOCUMENT_SETTINGS_UNAVAILABLE", 503);
        return mode.title();
    }

    private void finish(String id, GlobalDocumentSettings.Connection source, GlobalDocumentSettings.Draft draft,
            GlobalDocumentSettings.Connection target) {
        store.transaction(current -> {
            var operation = require(current, id);
            if (!"RUNNING".equals(operation.status())) throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            if (operation.cancelRequested()) {
                var cancelled = operation.transition("CANCELLED", operation.stage(), operation.journal(), null, false, clock.instant());
                return new GlobalSettingsStore.Change<>(snapshot(current, current.active(), null, cancelled,
                        current.idempotencyRecords()), null);
            }
            if (current.active() == null || !source.connectionId().equals(current.active().connectionId())
                    || source.connectionVersion() != current.active().connectionVersion()
                    || current.draft() == null || !draft.draftId().equals(current.draft().draftId())
                    || draft.revision() != current.draft().revision())
                throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
            var completed = operation.transition("SUCCEEDED", "ACTIVATE", operation.journal(), null, false, clock.instant());
            return new GlobalSettingsStore.Change<>(snapshot(current, target, null, completed,
                    current.idempotencyRecords()), null);
        });
    }

    private void cancelAtSafePoint(String id) {
        store.transaction(current -> {
            var operation = require(current, id);
            if (TERMINAL.contains(operation.status())) return unchanged(current, null);
            var cancelled = operation.transition("CANCELLED", operation.stage(), operation.journal(), null, false, clock.instant());
            return new GlobalSettingsStore.Change<>(snapshot(current, current.active(), null, cancelled,
                    current.idempotencyRecords()), null);
        });
    }

    private void fail(String id, String code, String status, boolean retryable) {
        try {
            update(id, current -> current.transition(status, current.stage(), current.journal(), code, retryable, clock.instant()));
        } catch (SettingsException unavailable) {
            if (unavailable.status() != 503 && unavailable.status() != 404) throw unavailable;
        }
    }

    private void update(String id, Function<GlobalDocumentSettings.Operation, GlobalDocumentSettings.Operation> action) {
        store.transaction(current -> new GlobalSettingsStore.Change<>(snapshot(current, current.active(), current.draft(),
                action.apply(require(current, id)), current.idempotencyRecords()), null));
    }

    private GlobalDocumentSettings.Connection targetConnection(GlobalDocumentSettings.Connection source,
            GlobalDocumentSettings.Draft draft) {
        DocumentStructure structure = draft.preparedStructure();
        return new GlobalDocumentSettings.Connection("connection_" + UUID.randomUUID(), source.connectionVersion() + 1,
                draft.provider(), draft.location(), draft.credentials(), structure.rootId(), structure.meetingsPageId(),
                structure.participantsPageId(), clock.instant());
    }

    private GlobalDocumentSettings.Operation require(GlobalDocumentSettings current, String id) {
        var operation = current.operation();
        if (operation == null || !operation.operationId().equals(id))
            throw new SettingsException("DOCUMENT_OPERATION_NOT_FOUND", 404);
        return operation;
    }

    private GlobalDocumentSettings snapshot(GlobalDocumentSettings current, GlobalDocumentSettings.Connection active,
            GlobalDocumentSettings.Draft draft, GlobalDocumentSettings.Operation operation,
            Map<String, GlobalDocumentSettings.Replay> records) {
        var history = new HashMap<>(current.operationHistory());
        if (current.operation() != null && !current.operation().operationId().equals(operation.operationId()))
            history.put(current.operation().operationId(), current.operation());
        history.values().removeIf(old -> !old.updatedAt().plus(Duration.ofDays(30)).isAfter(clock.instant()));
        return new GlobalDocumentSettings(1, current.version() + 1, active, draft, operation, records, Map.copyOf(history), current.slackNotification(), current.emailNotification());
    }

    private static OperationSummary summary(GlobalDocumentSettings.Operation operation, long version) {
        List<String> actions = switch (operation.status()) {
            case "PENDING", "RUNNING" -> operation.cancelRequested() ? List.of() : List.of("CANCEL");
            case "INTERRUPTED" -> List.of("RETRY", "CANCEL");
            case "FAILED" -> operation.retryable() ? List.of("RETRY", "CANCEL") : List.of("CANCEL");
            case "RECONCILIATION_REQUIRED" -> List.of("REVIEW_PROVIDER", "CANCEL");
            default -> List.of();
        };
        return new OperationSummary(operation.operationId(), operation.type(), operation.status(), operation.stage(),
                new Counts(operation.participantsTotal(), operation.participantsCopied(), operation.meetingsTotal(),
                        operation.meetingsCopied()), operation.cancelRequested(), operation.errorCode(), operation.retryable(), actions, version);
    }

    private static MutationResult mutation(String operationId, String status, long version, int httpStatus) {
        String json = JSON.writeValueAsString(Map.of("data", Map.of("operationId", operationId,
                "status", status, "version", version)));
        return new MutationResult(operationId, httpStatus, json, version);
    }

    private static MutationResult replay(GlobalDocumentSettings current, String key, String hmac) {
        var record = current.idempotencyRecords().get(key);
        if (record == null) return null;
        if (!record.requestHmac().equals(hmac)) throw new SettingsException("IDEMPOTENCY_KEY_CONFLICT", 409);
        var data = JSON.readTree(record.responseJson()).path("data");
        return new MutationResult(data.path("operationId").asString(), record.status(), record.responseJson(), record.version());
    }

    private static GlobalSettingsStore.Change<Acceptance> unchanged(GlobalDocumentSettings current, Acceptance result) {
        return new GlobalSettingsStore.Change<>(current, result);
    }
    private static void checkVersion(GlobalDocumentSettings current, long expected) {
        if (current.version() != expected) throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
    }
    private static void validate(Request request, String key) {
        validateKey(key);
        if (request == null || request.draftId() == null || request.draftId().isBlank() || request.draftRevision() < 1
                || !Boolean.TRUE.equals(request.confirmSourcePreserved())
                || !("COPY_ALL".equals(request.mode()) || "START_EMPTY".equals(request.mode())))
            throw SettingsException.invalid();
    }
    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw SettingsException.invalid();
    }
    private static <T> GlobalSettingsStore.Change<T> unchanged(GlobalDocumentSettings current, T value) {
        return new GlobalSettingsStore.Change<>(current, value);
    }

    public record Request(String draftId, long draftRevision, String mode, Boolean confirmSourcePreserved) { }
    public record MutationResult(String operationId, int status, String responseJson, long version) { }
    public record OperationSummary(String operationId, String type, String status, String stage,
            Counts counts,
            boolean cancelRequested, String errorCode, boolean retryable, List<String> allowedActions, long version) { }
    public record Counts(long participantsTotal, long participantsCopied, long meetingsTotal, long meetingsCopied) { }
    private record Acceptance(MutationResult result, String operationId) { }
    private record RunContext(GlobalDocumentSettings.Connection active, GlobalDocumentSettings.Draft draft) { }
}
