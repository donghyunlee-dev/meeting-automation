package com.meetingautomation;

import com.meetingautomation.document.bootstrap.*;
import com.meetingautomation.document.settings.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentBootstrapUseCaseTests {
    @Test void bootActivationFirstConnectionIsAtomicAndReplaySchedulesOnce() {
        var fixture=new Fixture(); fixture.test();
        var response=fixture.initialize();
        var replay=fixture.bootstrap.initialize(0,"initialize",fixture.draftId,1,true);
        assertThat(replay).isEqualTo(response); assertThat(fixture.queue.tasks).hasSize(1);
        assertThat(fixture.store.read().active()).isNull();
        fixture.queue.run();
        var state=fixture.store.read();
        assertThat(state.active().rootId()).isEqualTo("page1");
        assertThat(state.draft()).isNull(); assertThat(state.operation().status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.settings.status().status()).isEqualTo("READY");
        assertThat(fixture.readOnlyChecks).hasSize(2); // TEST plus fresh pre-write verification.
    }
    @Test void bootActivationPreservesExistingConnectionAndPreparesCandidate() {
        var fixture=new Fixture(DocumentSettingsFixtures.activeNotion("original-secret","original-root"));
        var original=fixture.store.read().active(); fixture.test(); fixture.initialize(); fixture.queue.run();
        var state=fixture.store.read();
        assertThat(state.active()).isEqualTo(original); assertThat(state.draft().state()).isEqualTo("PREPARED");
        assertThat(state.draft().preparedStructure().rootId()).isEqualTo("page1");
    }
    @Test void bootActivationExpiredTestOrChangedRevisionNeverSchedulesWrites() {
        var fixture=new Fixture(); fixture.test(); fixture.clock.now=fixture.clock.now.plusSeconds(600);
        assertThatThrownBy(fixture::initialize).isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_TEST_REQUIRED");
        assertThat(fixture.queue.tasks).isEmpty(); assertThat(fixture.pages.created).isEmpty();
        var fresh=new Fixture(); fresh.test();
        assertThatThrownBy(() -> fresh.bootstrap.initialize(fresh.store.read().version(),"initialize",fresh.draftId,2,true))
            .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_SETTINGS_VERSION_CONFLICT");
        assertThat(fresh.queue.tasks).isEmpty();
    }
    @Test void bootActivationFreshPermissionDenialDoesNotWriteOrActivate() {
        var fixture=new Fixture(); fixture.test(); fixture.deny=true; fixture.initialize(); fixture.queue.run();
        assertThat(fixture.store.read().active()).isNull(); assertThat(fixture.pages.created).isEmpty();
        assertThat(fixture.store.read().operation().status()).isEqualTo("FAILED");
        assertThat(fixture.settings.status().status()).isEqualTo("ATTENTION_REQUIRED");
        var operation=fixture.store.read().operation();
        assertThat(fixture.bootstrap.operation(operation.operationId()).allowedActions()).containsExactly("CANCEL");
        assertThatThrownBy(() -> fixture.bootstrap.resumeInterrupted(operation.operationId()))
            .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        assertThat(fixture.queue.tasks).isEmpty();
    }
    @Test void bootOperationFailureActionsFollowRetryabilityAndReconciliation() {
        var fixture=new Fixture(); fixture.test(); fixture.initialize();
        var operation=fixture.store.read().operation();
        assertThat(DocumentBootstrapUseCase.summary(operation.transition("FAILED","STRUCTURE",Map.of(),"DOCUMENT_FAILED",true,fixture.clock.instant()),1).allowedActions())
            .containsExactly("RETRY","CANCEL");
        assertThat(DocumentBootstrapUseCase.summary(operation.transition("FAILED","STRUCTURE",Map.of(),"DOCUMENT_STRUCTURE_CONFLICT",false,fixture.clock.instant()),1).allowedActions())
            .containsExactly("REVIEW_PROVIDER","CANCEL");
        assertThat(DocumentBootstrapUseCase.summary(operation.transition("RECONCILIATION_REQUIRED","STRUCTURE",Map.of(),"DOCUMENT_RECONCILIATION_REQUIRED",false,fixture.clock.instant()),1).allowedActions())
            .containsExactly("REVIEW_PROVIDER","CANCEL");
        assertThat(DocumentBootstrapUseCase.summary(operation.transition("INTERRUPTED","STRUCTURE",Map.of(),null,false,fixture.clock.instant()),1).allowedActions())
            .containsExactly("RETRY","CANCEL");
    }
    @Test void bootRecoveryRestartInterruptsWithoutAutomaticRetryThenExplicitContinuationReusesMarker() {
        var fixture=new Fixture(); fixture.test(); fixture.pages.loseNextResponse=true;
        fixture.initialize(); fixture.queue.run();
        var failed=fixture.store.read().operation();
        fixture.store.transaction(current -> new GlobalSettingsStore.Change<>(new GlobalDocumentSettings(1,current.version()+1,
            current.active(),current.draft(),failed.transition("RUNNING","STRUCTURE",failed.journal(),null,false,fixture.clock.instant()),current.idempotencyRecords()),null));
        var resumed=fixture.newUseCase();
        assertThat(fixture.store.read().operation().status()).isEqualTo("INTERRUPTED");
        assertThat(fixture.queue.tasks).isEmpty(); assertThat(fixture.pages.created).containsExactly("Meeting Automation");
        fixture.clock.now=fixture.clock.now.plusSeconds(700); // Public initialize remains TESTED-bound; continuation is internal.
        assertThatThrownBy(() -> resumed.initialize(fixture.store.read().version(),"new-initialize",fixture.draftId,1,true))
            .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        resumed.resumeInterrupted(failed.operationId()); fixture.queue.run();
        assertThat(fixture.pages.created).containsExactly("Meeting Automation","Meetings","Participants");
        assertThat(fixture.store.read().active()).isNotNull();
    }
    @Test void bootOperationPublicSummaryNeverSerializesJournalOrCredentials() {
        var fixture=new Fixture(); fixture.test(); fixture.initialize(); fixture.queue.run();
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
            fixture.bootstrap.operation(fixture.store.read().operation().operationId()));
        assertThat(json).contains("\"counts\"","\"version\"","\"ACTIVATE\"")
            .doesNotContain("journal","marker","synthetic-secret","credentials","parentId","accountEmail");
    }
    @Test void bootOperationKeepsCompletedSummaryWhenPreparingAnotherDraft() {
        var fixture=new Fixture(); fixture.test(); fixture.initialize(); fixture.queue.run();
        String first=fixture.store.read().operation().operationId();
        fixture.settings.saveDraft(fixture.store.read().version(),"draft2",Fixture.INPUT);
        fixture.draftId=fixture.store.read().draft().draftId(); fixture.test();
        fixture.bootstrap.initialize(fixture.store.read().version(),"initialize2",fixture.draftId,1,true);
        assertThat(fixture.bootstrap.operation(first).status()).isEqualTo("SUCCEEDED");
        fixture.clock.now=fixture.clock.now.plus(Duration.ofDays(31)); fixture.settings.status();
        assertThat(fixture.store.read().operationHistory()).isEmpty();
        assertThat(fixture.store.read().draft()).isNotNull(); // A live operation retains its bound draft.
        assertThatThrownBy(() -> fixture.bootstrap.operation(first)).isInstanceOf(SettingsException.class)
            .extracting("code").isEqualTo("DOCUMENT_OPERATION_NOT_FOUND");
    }
    @Test void bootExternalCreateDoesNotHoldGlobalStoreLock() throws Exception {
        var fixture=new Fixture(); fixture.test();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        fixture.providerPages=new BootstrapPages() {
            public List<Page> children(String parent) { return fixture.pages.children(parent); }
            public Page read(String id) { return fixture.pages.read(id); }
            public String create(String parent,String title,String marker) {
                entered.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Provider fixture release timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return fixture.pages.create(parent,title,marker);
            }
        };
        fixture.initialize();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var work=pool.submit(fixture.queue::run);
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(pool.submit(fixture.store::read).get(1,TimeUnit.SECONDS).operation().status()).isEqualTo("RUNNING");
                assertThat(pool.submit(fixture.settings::status).get(1,TimeUnit.SECONDS).status()).isEqualTo("CONFIGURING");
            } finally { release.countDown(); }
            work.get(60,TimeUnit.SECONDS);
        }
    }
    @Test void bootIntentPersistenceFailureStopsBeforeProviderCreate() {
        var guarded=new GuardedStore(DocumentSettingsFixtures.empty()); var fixture=new Fixture(guarded); fixture.test();
        guarded.rejectIntent=true; fixture.initialize(); fixture.queue.run();
        assertThat(fixture.pages.created).isEmpty(); assertThat(fixture.store.read().active()).isNull();
    }
    @Test void bootActivationSnapshotFailurePreservesJournalAndExplicitRecoveryCreatesNothingMore() {
        var guarded=new GuardedStore(DocumentSettingsFixtures.empty()); var fixture=new Fixture(guarded); fixture.test();
        guarded.rejectActivation=true; fixture.initialize(); fixture.queue.run();
        assertThat(fixture.store.read().active()).isNull();
        assertThat(fixture.store.read().operation().journal()).hasSize(3);
        guarded.rejectActivation=false;
        fixture.bootstrap.resumeInterrupted(fixture.store.read().operation().operationId()); fixture.queue.run();
        assertThat(fixture.pages.created).containsExactly("Meeting Automation","Meetings","Participants");
        assertThat(fixture.store.read().active()).isNotNull();
    }
    static final class Fixture {
        static final DocumentDraftInput INPUT=new DocumentDraftInput("NOTION",Map.of("parentPageId","aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),Map.of("token","synthetic-secret"),null);
        final GlobalSettingsStore store; final MutableClock clock=new MutableClock(); final Queue queue=new Queue();
        final DocumentStructureInitializerTests.Pages pages=new DocumentStructureInitializerTests.Pages();
        BootstrapPages providerPages=pages;
        final List<String> readOnlyChecks=new ArrayList<>(); boolean deny; String draftId;
        final GlobalSettingsUseCase settings; final DocumentBootstrapUseCase bootstrap;
        Fixture() { this(DocumentSettingsFixtures.empty()); }
        Fixture(GlobalSettingsStore store) {
            this.store=store; settings=new GlobalSettingsUseCase(store,this::verify,clock); bootstrap=newUseCase();
            settings.saveDraft(store.read().version(),"draft",INPUT); draftId=store.read().draft().draftId();
        }
        void verify(GlobalDocumentSettings.Draft draft) { readOnlyChecks.add(draft.draftId()); if(deny) throw new SettingsException("DOCUMENT_INITIALIZATION_FAILED",403); }
        DocumentBootstrapUseCase newUseCase() { return new DocumentBootstrapUseCase(store,this::verify,draft -> providerPages,queue,clock); }
        void test() { settings.testDraft(store.read().version(),"test"+store.read().version(),draftId,1); }
        GlobalSettingsUseCase.MutationResult initialize() { return bootstrap.initialize(store.read().version(),"initialize",draftId,1,true); }
    }
    static final class GuardedStore implements GlobalSettingsStore {
        final GlobalSettingsStore delegate; boolean rejectIntent,rejectActivation;
        GuardedStore(GlobalSettingsStore delegate) { this.delegate=delegate; }
        public GlobalDocumentSettings read() { return delegate.read(); }
        public String fingerprint(String request) { return delegate.fingerprint(request); }
        public <T> T transaction(java.util.function.Function<GlobalDocumentSettings,Change<T>> mutation) {
            return delegate.transaction(current -> {
                var change=mutation.apply(current);
                if(rejectIntent && change.snapshot().operation()!=null && !change.snapshot().operation().journal().isEmpty()
                    || rejectActivation && change.snapshot().active()!=null) throw SettingsException.storage();
                return change;
            });
        }
    }
    static final class Queue implements Executor {
        final List<Runnable> tasks=new ArrayList<>();
        public void execute(Runnable task) { tasks.add(task); }
        void run() { tasks.removeFirst().run(); }
    }
    static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-11T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
