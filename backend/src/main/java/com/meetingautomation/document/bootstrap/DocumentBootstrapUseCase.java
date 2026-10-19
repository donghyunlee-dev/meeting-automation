package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.settings.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class DocumentBootstrapUseCase {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final GlobalSettingsStore store;
    private final DocumentConnectionTester tester;
    private final BootstrapProviderFactory providers;
    private final Executor executor;
    private final Clock clock;
    private final Set<String> activeTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @org.springframework.beans.factory.annotation.Autowired
    public DocumentBootstrapUseCase(GlobalSettingsStore store, DocumentConnectionTester tester,
            BootstrapProviderFactory providers, BootstrapTaskRunner executor) {
        this(store,tester,providers,executor,Clock.systemUTC());
    }
    public DocumentBootstrapUseCase(GlobalSettingsStore store, DocumentConnectionTester tester,
            BootstrapProviderFactory providers, Executor executor, Clock clock) {
        this.store=store; this.tester=tester; this.providers=providers; this.executor=executor; this.clock=clock;
        // Startup only records interruption. It never schedules or retries external writes.
        try {
            store.transaction(current -> {
                var op=current.operation();
                if (op == null || !Set.of("PENDING","RUNNING").contains(op.status())) return unchanged(current,null);
                var interrupted=op.transition("INTERRUPTED",op.stage(),op.journal(),"DOCUMENT_FAILED",true,clock.instant());
                return new GlobalSettingsStore.Change<>(snapshot(current,current.active(),current.draft(),interrupted,current.idempotencyRecords()),null);
            });
        } catch (SettingsException unavailable) { if (unavailable.status()!=503) throw unavailable; }
    }
    public GlobalSettingsUseCase.MutationResult initialize(long version,String key,String draftId,long revision,boolean confirmed) {
        if (!confirmed || revision<1) throw SettingsException.invalid();
        String hmac=store.fingerprint("initialize:"+draftId+":"+revision+":true");
        Acceptance accepted=store.transaction(current -> {
            var replay=current.idempotencyRecords().get(key);
            if (replay != null) {
                if (!replay.requestHmac().equals(hmac)) throw new SettingsException("IDEMPOTENCY_KEY_CONFLICT",409);
                return unchanged(current,new Acceptance(new GlobalSettingsUseCase.MutationResult(replay.status(),replay.responseJson(),replay.version()),null));
            }
            if (version!=current.version()) throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT",412);
            if (current.operation()!=null && !Set.of("SUCCEEDED","CANCELLED").contains(current.operation().status()))
                throw new SettingsException("DOCUMENT_CHANGE_BUSY",409);
            var draft=current.draft();
            if (draft==null || !draft.draftId().equals(draftId) || !draft.expiresAt().isAfter(clock.instant()))
                throw new SettingsException("DOCUMENT_DRAFT_NOT_FOUND",404);
            if (revision!=draft.revision()) throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT",412);
            if (!"TESTED".equals(draft.state()) || draft.testResult()==null || !draft.testResult().expiresAt().isAfter(clock.instant())
                    || !draft.testResult().authenticated() || !draft.testResult().parentAccessible())
                throw new SettingsException("DOCUMENT_TEST_REQUIRED",409);
            String id="operation_"+UUID.randomUUID();
            var op=new GlobalDocumentSettings.Operation(id,"INITIALIZE","PENDING",draftId,revision,"PREFLIGHT",Map.of(),null,false,clock.instant());
            String json=JSON.writeValueAsString(Map.of("data",Map.of("operationId",id,"status","PENDING","version",current.version()+1)));
            var records=new HashMap<>(current.idempotencyRecords());
            records.put(key,new GlobalDocumentSettings.Replay(hmac,202,json,current.version()+1));
            var next=snapshot(current,current.active(),draftState(draft,"INITIALIZING",null),op,Map.copyOf(records));
            return new GlobalSettingsStore.Change<>(next,new Acceptance(new GlobalSettingsUseCase.MutationResult(202,json,next.version()),id));
        });
        if (accepted.operationId()!=null) schedule(accepted.operationId());
        return accepted.response();
    }
    public OperationSummary operation(String operationId) {
        var current=store.read();
        var op=current.operation();
        if (op!=null && op.operationId().equals(operationId) && "RUNNING".equals(op.status()) && !activeTasks.contains(operationId)) {
            current=store.transaction(state -> {
                var candidate=state.operation();
                if (candidate==null || !candidate.operationId().equals(operationId) || !"RUNNING".equals(candidate.status()) || activeTasks.contains(operationId))
                    return unchanged(state,state);
                var interrupted=candidate.transition("INTERRUPTED",candidate.stage(),candidate.journal(),"DOCUMENT_FAILED",true,clock.instant());
                var recovered=snapshot(state,state.active(),state.draft(),interrupted,state.idempotencyRecords());
                return new GlobalSettingsStore.Change<>(recovered,recovered);
            });
            op=current.operation();
        }
        if (op==null || !op.operationId().equals(operationId)) op=current.operationHistory().get(operationId);
        if (op==null || (Set.of("SUCCEEDED","CANCELLED").contains(op.status()) && !op.updatedAt().plus(Duration.ofDays(30)).isAfter(clock.instant())))
            throw new SettingsException("DOCUMENT_OPERATION_NOT_FOUND",404);
        return summary(op,current.version());
    }
    /** Internal continuation boundary for the later API-029 use case; not exposed by API-026. */
    public void resumeInterrupted(String operationId) {
        update(operationId,op -> {
            if (!Set.of("FAILED","INTERRUPTED").contains(op.status()) || ("FAILED".equals(op.status()) && !op.retryable()))
                throw new SettingsException("DOCUMENT_CHANGE_BUSY",409);
            return op.transition("PENDING","PREFLIGHT",op.journal(),null,false,clock.instant());
        });
        schedule(operationId);
    }
    public GlobalSettingsUseCase.MutationResult cancelInterrupted(long version,String key,String operationId) {
        String hmac=store.fingerprint("cancel-initialize:"+operationId);
        return store.transaction(current -> {
            var replay=current.idempotencyRecords().get(key);
            if (replay!=null) {
                if (!replay.requestHmac().equals(hmac)) throw new SettingsException("IDEMPOTENCY_KEY_CONFLICT",409);
                return unchanged(current,new GlobalSettingsUseCase.MutationResult(replay.status(),replay.responseJson(),replay.version()));
            }
            if (version!=current.version()) throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT",412);
            var operation=current.operation();
            if (operation==null || !operation.operationId().equals(operationId) || !"INITIALIZE".equals(operation.type())
                    || !(Set.of("PENDING","FAILED","INTERRUPTED","RECONCILIATION_REQUIRED").contains(operation.status())
                        || "RUNNING".equals(operation.status())))
                throw new SettingsException("DOCUMENT_CHANGE_BUSY",409);
            var cancelled=operation.transition("CANCELLED",operation.stage(),operation.journal(),null,false,clock.instant());
            long nextVersion=current.version()+1;
            String json=JSON.writeValueAsString(Map.of("data",Map.of("operationId",operationId,"status","CANCELLED","version",nextVersion)));
            var records=new HashMap<>(current.idempotencyRecords());
            records.put(key,new GlobalDocumentSettings.Replay(hmac,202,json,nextVersion));
            return new GlobalSettingsStore.Change<>(snapshot(current,current.active(),null,cancelled,Map.copyOf(records)),
                    new GlobalSettingsUseCase.MutationResult(202,json,nextVersion));
        });
    }
    public GlobalSettingsUseCase.MutationResult retryInterrupted(long version,String key,String operationId) {
        String hmac=store.fingerprint("retry-initialize:"+operationId);
        Acceptance accepted=store.transaction(current -> {
            var replay=current.idempotencyRecords().get(key);
            if (replay!=null) {
                if (!replay.requestHmac().equals(hmac)) throw new SettingsException("IDEMPOTENCY_KEY_CONFLICT",409);
                return unchanged(current,new Acceptance(new GlobalSettingsUseCase.MutationResult(replay.status(),replay.responseJson(),replay.version()),null));
            }
            if (version!=current.version()) throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT",412);
            var operation=current.operation();
            if (operation==null || !operation.operationId().equals(operationId) || !"INITIALIZE".equals(operation.type())
                    || !("INTERRUPTED".equals(operation.status()) || "FAILED".equals(operation.status()) && operation.retryable()))
                throw new SettingsException("DOCUMENT_CHANGE_BUSY",409);
            var resumed=operation.transition("PENDING","PREFLIGHT",operation.journal(),null,false,clock.instant());
            long nextVersion=current.version()+1;
            String json=JSON.writeValueAsString(Map.of("data",Map.of("operationId",operationId,"status","PENDING","version",nextVersion)));
            var records=new HashMap<>(current.idempotencyRecords());
            records.put(key,new GlobalDocumentSettings.Replay(hmac,202,json,nextVersion));
            return new GlobalSettingsStore.Change<>(snapshot(current,current.active(),current.draft(),resumed,Map.copyOf(records)),
                    new Acceptance(new GlobalSettingsUseCase.MutationResult(202,json,nextVersion),operationId));
        });
        if (accepted.operationId()!=null) schedule(accepted.operationId());
        return accepted.response();
    }
    private void schedule(String operationId) {
        try { executor.execute(() -> { activeTasks.add(operationId); try { run(operationId); } finally { activeTasks.remove(operationId); } }); }
        catch (RuntimeException schedulingFailure) { fail(operationId,"DOCUMENT_FAILED","INTERRUPTED",true); }
    }
    private void run(String id) {
        try {
            var draft=store.transaction(current -> {
                var op=require(current,id);
                if (!"PENDING".equals(op.status())) return unchanged(current,(GlobalDocumentSettings.Draft)null);
                var candidate=current.draft();
                if (candidate==null || !op.draftId().equals(candidate.draftId()) || op.draftRevision()!=candidate.revision())
                    throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT",412);
                var running=op.transition("RUNNING","PREFLIGHT",op.journal(),null,false,clock.instant());
                return new GlobalSettingsStore.Change<>(snapshot(current,current.active(),candidate,running,current.idempotencyRecords()),candidate);
            });
            if (draft==null) return;
            tester.verify(draft); // All provider calls execute outside the snapshot transaction.
            update(id,op -> op.transition("RUNNING","STRUCTURE",op.journal(),null,false,clock.instant()));
            var structure=new DocumentStructureInitializer(providers.create(draft)).initializeStructure(
                draft.location().get("parentPageId"),draft.reuseExistingRootId(),id,new DurableJournal(id));
            update(id,op -> op.transition("RUNNING","VERIFY",op.journal(),null,false,clock.instant()));
            complete(id,structure);
        } catch (BootstrapCancelledException cancelled) {
            return;
        } catch (SettingsException safe) {
            fail(id,safe.code(),"DOCUMENT_RECONCILIATION_REQUIRED".equals(safe.code())?"RECONCILIATION_REQUIRED":"FAILED",safe.status()>=500);
        } catch (DocumentProviderException safe) {
            fail(id,safe.code(),"FAILED",safe.retryable());
        } catch (LinkageError incompatibleRuntime) {
            fail(id,"DOCUMENT_FAILED","FAILED",false);
        } catch (RuntimeException ignored) { fail(id,"DOCUMENT_FAILED","FAILED",false); }
    }
    private void complete(String id,DocumentStructure structure) {
        store.transaction(current -> {
            var op=require(current,id); var draft=current.draft();
            if (!"RUNNING".equals(op.status()) || draft==null || !op.draftId().equals(draft.draftId()) || op.draftRevision()!=draft.revision())
                throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT",412);
            var active=current.active();
            if (active==null) {
                active=new GlobalDocumentSettings.Connection("connection_"+UUID.randomUUID(),1,draft.provider(),draft.location(),draft.credentials(),
                    structure.rootId(),structure.meetingsPageId(),structure.participantsPageId(),clock.instant());
                draft=null; // Remove the redundant draft credentials in the same snapshot as activation.
            } else draft=draftState(draft,"PREPARED",structure);
            var succeeded=op.transition("SUCCEEDED","ACTIVATE",op.journal(),null,false,clock.instant());
            return new GlobalSettingsStore.Change<>(snapshot(current,active,draft,succeeded,current.idempotencyRecords()),null);
        });
    }
    private void fail(String id,String code,String status,boolean retryable) {
        try { update(id,op -> op.transition(status,op.stage(),op.journal(),code,retryable,clock.instant())); }
        catch (BootstrapCancelledException cancelled) { return; }
        catch (SettingsException unavailable) {
            // A failed storage write must leave the durable RUNNING/intent state intact for restart reconciliation.
            if (unavailable.status()!=503 && unavailable.status()!=404) throw unavailable;
        }
    }
    private void update(String id,UnaryOperator<GlobalDocumentSettings.Operation> action) {
        store.transaction(current -> {
            var operation=require(current,id);
            if ("CANCELLED".equals(operation.status())) throw new BootstrapCancelledException();
            return new GlobalSettingsStore.Change<>(snapshot(current,current.active(),current.draft(),action.apply(operation),current.idempotencyRecords()),null);
        });
    }
    private static GlobalDocumentSettings.Operation require(GlobalDocumentSettings current,String id) {
        var op=current.operation();
        if (op==null || !id.equals(op.operationId())) throw new SettingsException("DOCUMENT_OPERATION_NOT_FOUND",404);
        return op;
    }
    private GlobalDocumentSettings snapshot(GlobalDocumentSettings current,GlobalDocumentSettings.Connection active,
            GlobalDocumentSettings.Draft draft,GlobalDocumentSettings.Operation op,Map<String,GlobalDocumentSettings.Replay> records) {
        var history=new HashMap<>(current.operationHistory());
        if (current.operation()!=null && !current.operation().operationId().equals(op.operationId()))
            history.put(current.operation().operationId(),current.operation());
        history.values().removeIf(old -> !old.updatedAt().plus(Duration.ofDays(30)).isAfter(clock.instant()));
        return new GlobalDocumentSettings(1,current.version()+1,active,draft,op,records,Map.copyOf(history));
    }
    private static GlobalDocumentSettings.Draft draftState(GlobalDocumentSettings.Draft draft,String state,DocumentStructure structure) {
        return new GlobalDocumentSettings.Draft(draft.draftId(),draft.revision(),draft.provider(),draft.location(),draft.credentials(),
            draft.reuseExistingRootId(),state,draft.testResult(),draft.expiresAt(),structure);
    }
    private static <T> GlobalSettingsStore.Change<T> unchanged(GlobalDocumentSettings current,T result) { return new GlobalSettingsStore.Change<>(current,result); }
    private record Acceptance(GlobalSettingsUseCase.MutationResult response,String operationId) { }
    private static final class BootstrapCancelledException extends RuntimeException { }
    public static OperationSummary summary(GlobalDocumentSettings.Operation op,long version) {
        List<String> actions=switch(op.status()) {
            case "FAILED" -> op.retryable() ? List.of("RETRY","CANCEL")
                : "DOCUMENT_STRUCTURE_CONFLICT".equals(op.errorCode()) ? List.of("REVIEW_PROVIDER","CANCEL") : List.of("CANCEL");
            case "INTERRUPTED" -> List.of("RETRY","CANCEL");
            case "RECONCILIATION_REQUIRED" -> List.of("REVIEW_PROVIDER","CANCEL");
            case "PENDING","RUNNING" -> List.of();
            default -> List.of();
        };
        List<String> steps=List.of("ROOT","MEETINGS","PARTICIPANTS","TEMPLATES");
        List<String> completed=steps.stream().filter(step -> op.journal().get(step)!=null && op.journal().get(step).pageId()!=null).toList();
        String current="STRUCTURE".equals(op.stage()) ? steps.stream().filter(step -> op.journal().get(step)==null || op.journal().get(step).pageId()==null).findFirst().orElse(null) : null;
        return new OperationSummary(op.operationId(),op.type(),op.status(),op.stage(),new Counts(0,0,0,0),false,op.errorCode(),op.retryable(),actions,version,completed,current);
    }
    public record OperationSummary(String operationId,String type,String status,String stage,Counts counts,boolean cancelRequested,
            String errorCode,boolean retryable,List<String> allowedActions,long version,List<String> completedSteps,String currentStep) { }
    public record Counts(long participantsTotal,long participantsCopied,long meetingsTotal,long meetingsCopied) { }
    private final class DurableJournal implements BootstrapJournal {
        private final String id;
        DurableJournal(String id) { this.id=id; }
        public GlobalDocumentSettings.Creation find(String step) {
            var operation=require(store.read(),id);
            if ("CANCELLED".equals(operation.status())) throw new BootstrapCancelledException();
            return operation.journal().get(step);
        }
        public void intent(String step,String parent,String title,String marker) {
            update(id,op -> {
                var journal=new LinkedHashMap<>(op.journal());
                if (journal.containsKey(step)) throw new SettingsException("DOCUMENT_RECONCILIATION_REQUIRED",409);
                journal.put(step,new GlobalDocumentSettings.Creation(parent,title,marker,null));
                return op.transition("RUNNING","STRUCTURE",journal,null,false,clock.instant());
            });
        }
        public void completed(String step,String pageId) {
            update(id,op -> {
                var journal=new LinkedHashMap<>(op.journal()); var entry=journal.get(step);
                if (entry==null) throw new SettingsException("DOCUMENT_RECONCILIATION_REQUIRED",409);
                journal.put(step,new GlobalDocumentSettings.Creation(entry.parentId(),entry.title(),entry.marker(),pageId));
                return op.transition("RUNNING","STRUCTURE",journal,null,false,clock.instant());
            });
        }
    }
}
