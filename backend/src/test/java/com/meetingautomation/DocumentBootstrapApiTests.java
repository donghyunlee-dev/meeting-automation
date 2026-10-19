package com.meetingautomation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.meetingautomation.document.settings.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "ALLOWED_ORIGINS=http://localhost:5173")
@AutoConfigureMockMvc
@Import({ConstraintViolationProbeService.class, DocumentBootstrapApiTests.Fixtures.class})
class DocumentBootstrapApiTests {
    @Autowired MockMvc mvc;
    @Autowired GlobalSettingsStore store;
    @Autowired GlobalSettingsUseCase settings;
    @Autowired DocumentBootstrapUseCaseTests.Queue queue;
    @BeforeEach void reset() {
        queue.tasks.clear();
        store.transaction(current -> new GlobalSettingsStore.Change<>(GlobalDocumentSettings.empty(),null));
    }

    @Test void bootActivationAcceptsOnlyExplicitTestedMatchingDraftAndReturnsOperation() throws Exception {
        settings.saveDraft(0, "draft", new DocumentDraftInput("NOTION", Map.of("parentPageId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), Map.of("token", "synthetic-secret"), null));
        var draft = store.read().draft();
        mvc.perform(post("/api/v1/document-setup/drafts/" + draft.draftId() + "/initialize").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "http://localhost:5173").header("X-Document-Setup-Request", "true")
            .header("If-Match", "\"1\"").header("Idempotency-Key", "initialize")
            .content("{\"draftRevision\":1,\"confirmStructure\":true}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DOCUMENT_TEST_REQUIRED"));
    }
    @Test void bootOperationLookupUsesSafeNotFoundEnvelope() throws Exception {
        mvc.perform(get("/api/v1/document-setup/operations/unknown"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DOCUMENT_OPERATION_NOT_FOUND"));
    }
    @Test void bootApiReturns202ThenSafeCurrentVersionOperationSummary() throws Exception {
        settings.saveDraft(0,"draft",new DocumentDraftInput("NOTION",Map.of("parentPageId","aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),Map.of("token","synthetic-secret"),null));
        var draft=store.read().draft(); settings.testDraft(1,"test",draft.draftId(),1);
        String path="/api/v1/document-setup/drafts/"+draft.draftId()+"/initialize";
        var request=post(path).contentType(MediaType.APPLICATION_JSON).header("Origin","http://localhost:5173")
            .header("X-Document-Setup-Request","true").header("If-Match","\"2\"").header("Idempotency-Key","initialize")
            .content("{\"draftRevision\":1,\"confirmStructure\":true}");
        var response=mvc.perform(request).andExpect(status().isAccepted()).andExpect(header().string("ETag","\"3\""))
            .andExpect(jsonPath("$.data.status").value("PENDING")).andExpect(jsonPath("$.data.version").value(3)).andReturn().getResponse().getContentAsString();
        mvc.perform(request).andExpect(status().isAccepted()).andExpect(content().string(response));
        assertEquals(1,queue.tasks.size()); queue.run();
        var operation=store.read().operation();
        var body=mvc.perform(get("/api/v1/document-setup/operations/"+operation.operationId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.data.type").value("INITIALIZE")).andExpect(jsonPath("$.data.stage").value("ACTIVATE"))
            .andExpect(jsonPath("$.data.counts.participantsTotal").value(0)).andExpect(jsonPath("$.data.allowedActions").isEmpty())
            .andExpect(jsonPath("$.data.journal").doesNotExist()).andExpect(jsonPath("$.data.credentials").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("synthetic"));
        mvc.perform(get("/api/v1/document-setup")).andExpect(jsonPath("$.data.status").value("READY"))
            .andExpect(jsonPath("$.data.active.rootId").value("page1"))
            .andExpect(jsonPath("$.data.operation.stage").doesNotExist()).andExpect(jsonPath("$.data.operation.journal").doesNotExist());
    }
    @Test void bootApiRequiresConfirmationAndTrustedOriginBeforeWrites() throws Exception {
        mvc.perform(post("/api/v1/document-setup/drafts/draft/initialize").contentType(MediaType.APPLICATION_JSON)
            .header("Origin","http://localhost:5173").header("X-Document-Setup-Request","true")
            .header("If-Match","\"0\"").header("Idempotency-Key","initialize").content("{\"draftRevision\":1,\"confirmStructure\":false}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/document-setup/drafts/draft/initialize").contentType(MediaType.APPLICATION_JSON)
            .header("Origin","https://evil.example").content("{\"draftRevision\":1,\"confirmStructure\":true}"))
            .andExpect(status().isForbidden());
        assertTrue(queue.tasks.isEmpty()); assertNull(store.read().operation());
    }
    @TestConfiguration(proxyBeanMethods = false) static class Fixtures {
        @Bean @Primary GlobalSettingsStore fixtureStore() { return DocumentSettingsFixtures.empty(); }
        @Bean @Primary DocumentConnectionTester fixtureTester() { return draft -> {}; }
        @Bean DocumentBootstrapUseCaseTests.Queue bootstrapQueue() { return new DocumentBootstrapUseCaseTests.Queue(); }
        @Bean @Primary com.meetingautomation.document.bootstrap.DocumentBootstrapUseCase fixtureBootstrap(GlobalSettingsStore store,
                DocumentConnectionTester tester,DocumentBootstrapUseCaseTests.Queue queue) {
            return new com.meetingautomation.document.bootstrap.DocumentBootstrapUseCase(store,tester,
                draft -> new DocumentStructureInitializerTests.Pages(),queue,java.time.Clock.systemUTC());
        }
    }
}
