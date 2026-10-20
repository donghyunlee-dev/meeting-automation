package com.meetingautomation;

import com.meetingautomation.document.bootstrap.*;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.settings.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @Test void bootLinkageFailureDoesNotLeaveOperationRunningForever() {
        var fixture=new Fixture(); fixture.test(); fixture.pages.createLinkageError=new NoSuchMethodError("bootstrap provider signature changed");
        fixture.initialize(); fixture.queue.run();

        assertThat(fixture.store.read().operation().status()).isEqualTo("FAILED");
        assertThat(fixture.store.read().operation().errorCode()).isEqualTo("DOCUMENT_FAILED");
        assertThat(fixture.store.read().active()).isNull();
        assertThat(fixture.pages.created).isEmpty();
    }
    @Test void bootInterruptedInitializationCanBeSafelyDismissedToUnlockSetup() {
        var fixture=new Fixture(); fixture.test(); fixture.initialize();
        var operationId=fixture.store.read().operation().operationId();
        var restarted=fixture.newUseCase();
        assertThat(restarted.operation(operationId).status()).isEqualTo("INTERRUPTED");

        restarted.cancelInterrupted(fixture.store.read().version(),"cancel-initialize",operationId);

        assertThat(fixture.store.read().operation().status()).isEqualTo("CANCELLED");
        assertThat(fixture.store.read().draft()).isNull();
        assertThat(fixture.settings.status().status()).isEqualTo("UNCONFIGURED");
    }
    @Test void bootOrphanedRunningInitializationCanBeRecoveredAndDismissedToUnlockSetup() {
        var fixture=new Fixture(); fixture.test(); fixture.initialize();
        var operationId=fixture.store.read().operation().operationId();
        fixture.store.transaction(current -> new GlobalSettingsStore.Change<>(new GlobalDocumentSettings(
                1,current.version()+1,current.active(),current.draft(),
                current.operation().transition("RUNNING","STRUCTURE",current.operation().journal(),null,false,fixture.clock.instant()),
                current.idempotencyRecords(),current.operationHistory()),null));

        var operation=fixture.bootstrap.operation(operationId);
        assertThat(operation.status()).isEqualTo("INTERRUPTED");
        assertThat(operation.allowedActions()).containsExactly("RETRY","CANCEL");

        fixture.bootstrap.cancelInterrupted(fixture.store.read().version(),"cancel-orphaned",operationId);
        fixture.queue.run();

        assertThat(fixture.store.read().operation().status()).isEqualTo("CANCELLED");
        assertThat(fixture.store.read().draft()).isNull();
        assertThat(fixture.settings.status().status()).isEqualTo("UNCONFIGURED");
        assertThat(fixture.pages.created).isEmpty();
    }
    @Test void bootActiveInitializationStopsAtTheNextSafeBoundaryAfterCancel() throws Exception {
        var fixture=new Fixture(); fixture.test();
        var providerEntered=new CountDownLatch(1); var continueProvider=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        var active=new DocumentBootstrapUseCase(fixture.store,draft -> {
            providerEntered.countDown();
            try { if (!continueProvider.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("test provider timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        },draft -> fixture.providerPages,executor,fixture.clock);
        try {
            active.initialize(fixture.store.read().version(),"active-initialize",fixture.draftId,1,true);
            assertThat(providerEntered.await(2,TimeUnit.SECONDS)).isTrue();
            var operationId=fixture.store.read().operation().operationId();

            assertThat(active.operation(operationId).status()).isEqualTo("RUNNING");
            active.cancelInterrupted(fixture.store.read().version(),"cancel-active",operationId);
            assertThat(fixture.store.read().operation().status()).isEqualTo("CANCELLED");
        } finally {
            continueProvider.countDown(); executor.shutdown();
            assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(fixture.store.read().operation().status()).isEqualTo("CANCELLED");
        assertThat(fixture.store.read().active()).isNull();
        assertThat(fixture.store.read().draft()).isNull();
        assertThat(fixture.pages.created).isEmpty();
    }
    @Test void bootCancellationDuringCreateKeepsTheIntentAndStopsBeforeCreatingTheNextPage() throws Exception {
        var fixture=new Fixture(); fixture.test();
        var providerEntered=new CountDownLatch(1); var continueProvider=new CountDownLatch(1);
        var backingPages=new DocumentStructureInitializerTests.Pages();
        var blockedPages=new BootstrapPages() {
            public List<Page> children(String parent) { return backingPages.children(parent); }
            public Page read(String id) { return backingPages.read(id); }
            public String create(String parent,String title,String marker) {
                providerEntered.countDown();
                try { if (!continueProvider.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("test provider timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return backingPages.create(parent,title,marker);
            }
        };
        fixture.providerPages=blockedPages;
        var executor=Executors.newSingleThreadExecutor();
        var active=new DocumentBootstrapUseCase(fixture.store,fixture::verify,draft -> fixture.providerPages,executor,fixture.clock);
        try {
            active.initialize(fixture.store.read().version(),"cancel-during-create",fixture.draftId,1,true);
            assertThat(providerEntered.await(2,TimeUnit.SECONDS)).isTrue();
            var operationId=fixture.store.read().operation().operationId();
            active.cancelInterrupted(fixture.store.read().version(),"cancel-active-create",operationId);
        } finally {
            continueProvider.countDown(); executor.shutdown();
            assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }

        var operation=fixture.store.read().operation();
        assertThat(operation.status()).isEqualTo("CANCELLED");
        assertThat(operation.journal()).containsKey("ROOT");
        assertThat(operation.journal().get("ROOT").pageId()).isNull();
        assertThat(backingPages.created).containsExactly("Meeting Automation");
    }
    @Test void bootInterruptedInitializationCanResumeTheSameIdempotentOperation() {
        var fixture=new Fixture(); fixture.test(); fixture.initialize();
        var operationId=fixture.store.read().operation().operationId();
        fixture.queue.tasks.clear();
        var restarted=fixture.newUseCase();
        var result=restarted.retryInterrupted(fixture.store.read().version(),"retry-initialize",operationId);

        assertThat(result.status()).isEqualTo(202);
        assertThat(fixture.store.read().operation().status()).isEqualTo("PENDING");
        assertThat(fixture.queue.tasks).hasSize(1);
        fixture.queue.run();
        assertThat(fixture.store.read().operation().status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.pages.created).containsExactly("Meeting Automation","Meetings","Participants","Meeting Data","Templates");
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
    @Test void bootOperationSummaryExposesCompletedAndCurrentStructureSteps() {
        var fixture=new Fixture(); fixture.test(); fixture.initialize();
        var operation=fixture.store.read().operation();
        var journal=new LinkedHashMap<String,GlobalDocumentSettings.Creation>();
        journal.put("ROOT",new GlobalDocumentSettings.Creation("parent","Meeting Automation","marker-root","root-id"));
        journal.put("MEETINGS",new GlobalDocumentSettings.Creation("root-id","Meetings","marker-meetings",null));
        var progress=operation.transition("RUNNING","STRUCTURE",journal,null,false,fixture.clock.instant());

        var summary=DocumentBootstrapUseCase.summary(progress,fixture.store.read().version());

        assertThat(summary.completedSteps()).containsExactly("ROOT");
        assertThat(summary.currentStep()).isEqualTo("MEETINGS");
    }
    @Test void bootProviderPermissionFailureRemainsNonRetryableInDurableOperation() {
        var fixture=new Fixture(); fixture.test();
        fixture.verificationFailure=DocumentProviderException.documentFailed(false);
        fixture.initialize(); fixture.queue.run();
        var operation=fixture.store.read().operation();
        assertThat(operation.errorCode()).isEqualTo("DOCUMENT_FAILED");
        assertThat(operation.retryable()).isFalse();
        assertThat(fixture.bootstrap.operation(operation.operationId()).allowedActions()).containsExactly("CANCEL");
        assertThatThrownBy(() -> fixture.bootstrap.resumeInterrupted(operation.operationId()))
            .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        assertThat(fixture.pages.created).isEmpty(); assertThat(fixture.store.read().active()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"TIMEOUT","EMPTY","{}","{\"id\":\"invalid\"}"})
    void bootRecoveryUnknownCreateOutcomePreservesMarkerAndRequiresProviderReview(String outcome) {
        var fixture=new Fixture(); fixture.test();
        var builder=org.springframework.web.client.RestClient.builder();
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var marker=new java.util.concurrent.atomic.AtomicReference<String>(); var writes=new java.util.concurrent.atomic.AtomicInteger();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),request ->
            assertThat(request.getMethod()).isIn(org.springframework.http.HttpMethod.GET,org.springframework.http.HttpMethod.POST))
            .andRespond(request -> {
                if(request.getMethod()==org.springframework.http.HttpMethod.GET) {
                    var response=new org.springframework.mock.http.client.MockClientHttpResponse(
                        "{\"results\":[],\"has_more\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8),org.springframework.http.HttpStatus.OK);
                    response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON); return response;
                }
                writes.incrementAndGet();
                var body=tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                    ((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString());
                marker.set(body.path("children").get(0).path("paragraph").path("rich_text").get(0).path("text").path("content").asText());
                if("TIMEOUT".equals(outcome)) throw new java.net.SocketTimeoutException("synthetic-secret provider-original");
                var response=new org.springframework.mock.http.client.MockClientHttpResponse(
                    ("EMPTY".equals(outcome)?"":outcome).getBytes(java.nio.charset.StandardCharsets.UTF_8),org.springframework.http.HttpStatus.OK);
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON); return response;
            });
        fixture.providerPages=new NotionBootstrapPages(builder,"synthetic-secret");
        fixture.initialize(); fixture.queue.run();
        var operation=fixture.store.read().operation();
        assertThat(operation.status()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(operation.errorCode()).isEqualTo("DOCUMENT_RECONCILIATION_REQUIRED");
        assertThat(operation.retryable()).isFalse();
        assertThat(fixture.bootstrap.operation(operation.operationId()).allowedActions()).containsExactly("REVIEW_PROVIDER","CANCEL");
        assertThat(operation.journal().get("ROOT").marker()).isEqualTo(marker.get()).isNotBlank();
        assertThat(operation.journal().get("ROOT").pageId()).isNull();
        assertThat(fixture.store.read().active()).isNull(); assertThat(writes.get()).isEqualTo(1);
        assertThat(fixture.queue.tasks).isEmpty(); server.verify();
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
        assertThat(fixture.pages.created).containsExactly("Meeting Automation","Meetings","Participants","Meeting Data","Templates");
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
        assertThat(fixture.store.read().operation().journal()).hasSize(5);
        guarded.rejectActivation=false;
        fixture.bootstrap.resumeInterrupted(fixture.store.read().operation().operationId()); fixture.queue.run();
        assertThat(fixture.pages.created).containsExactly("Meeting Automation","Meetings","Participants","Meeting Data","Templates");
        assertThat(fixture.store.read().active()).isNotNull();
    }
    static final class Fixture {
        static final DocumentDraftInput INPUT=new DocumentDraftInput("NOTION",Map.of("parentPageId","aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),Map.of("token","synthetic-secret"),null);
        final GlobalSettingsStore store; final MutableClock clock=new MutableClock(); final Queue queue=new Queue();
        final DocumentStructureInitializerTests.Pages pages=new DocumentStructureInitializerTests.Pages();
        BootstrapPages providerPages=pages;
        final List<String> readOnlyChecks=new ArrayList<>(); boolean deny; String draftId; DocumentProviderException verificationFailure;
        final GlobalSettingsUseCase settings; final DocumentBootstrapUseCase bootstrap;
        Fixture() { this(DocumentSettingsFixtures.empty()); }
        Fixture(GlobalSettingsStore store) {
            this.store=store; settings=new GlobalSettingsUseCase(store,this::verify,clock); bootstrap=newUseCase();
            settings.saveDraft(store.read().version(),"draft",INPUT); draftId=store.read().draft().draftId();
        }
        void verify(GlobalDocumentSettings.Draft draft) {
            readOnlyChecks.add(draft.draftId());
            if(verificationFailure!=null) throw verificationFailure;
            if(deny) throw new SettingsException("DOCUMENT_INITIALIZATION_FAILED",403);
        }
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
