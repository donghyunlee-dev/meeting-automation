package com.meetingautomation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {"DOCUMENT_PROVIDER=NOTION", "NOTION_TOKEN=ignored-env-token", "ALLOWED_ORIGINS=http://localhost:5173"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(ConstraintViolationProbeService.class)
class DocumentSetupApiTests {
    static final Path DIRECTORY = directory();
    static Path directory() { try { return Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".document-settings-test-"); } catch (Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("DOCUMENT_SETTINGS_DIR", DIRECTORY::toString);
        registry.add("DOCUMENT_SETTINGS_ENCRYPTION_KEY", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }
    @Autowired MockMvc mvc;

    @Test void setupEmptyAndDraftPersistenceConcurrencySecret() throws Exception {
        mvc.perform(get("/api/v1/document-setup")).andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.data.version").value(0))
            .andExpect(jsonPath("$.data.status").value("UNCONFIGURED"));
        mvc.perform(get("/api/v1/app-config")).andExpect(jsonPath("$.data.document.provider").doesNotExist())
            .andExpect(jsonPath("$.data.setup.required").value(true));
        String payload = "{\"provider\":\"NOTION\",\"location\":{\"parentPageId\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"},\"credentials\":{\"token\":\"synthetic-secret-token\"}}";
        var first = mvc.perform(post("/api/v1/document-setup/drafts").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "http://localhost:5173").header("X-Document-Setup-Request", "true")
            .header("If-Match", "\"0\"").header("Idempotency-Key", "save-1").content(payload))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.version").value(1)).andReturn();
        assertFalse(first.getResponse().getContentAsString().contains("synthetic-secret-token"));
        mvc.perform(post("/api/v1/document-setup/drafts").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "http://localhost:5173").header("X-Document-Setup-Request", "true")
            .header("If-Match", "\"0\"").header("Idempotency-Key", "save-1").content(payload))
            .andExpect(status().isCreated()).andExpect(content().string(first.getResponse().getContentAsString()));
        mvc.perform(post("/api/v1/document-setup/drafts").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "http://localhost:5173").header("X-Document-Setup-Request", "true")
            .header("If-Match", "\"0\"").header("Idempotency-Key", "save-2").content(payload)).andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/v1/document-setup/drafts").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "http://localhost:5173").header("X-Document-Setup-Request", "true")
            .header("If-Match", "\"1\"").header("Idempotency-Key", "save-1").content(payload.replace("synthetic-secret-token", "different-secret")))
            .andExpect(status().isConflict());
        assertFalse(Files.readString(DIRECTORY.resolve("settings.enc")).contains("synthetic-secret-token"));
    }
    @Test void setupSecretRejectsUntrustedOriginBeforeSaving() throws Exception {
        mvc.perform(post("/api/v1/document-setup/drafts").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "https://untrusted.example").header("If-Match", "\"0\"")
            .header("X-Document-Setup-Request", "true").header("Idempotency-Key", "bad").content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("DOCUMENT_SETUP_ORIGIN_REJECTED"));
    }
    @Test void setupSecretHeadersAndInputsAreValidated() throws Exception {
        for (String header : java.util.List.of("If-Match", "Idempotency-Key", "X-Document-Setup-Request")) {
            var request = post("/api/v1/document-setup/drafts").contentType(MediaType.APPLICATION_JSON).header("Origin", "http://localhost:5173").content("{}");
            if (!header.equals("If-Match")) request.header("If-Match", "\"0\"");
            if (!header.equals("Idempotency-Key")) request.header("Idempotency-Key", "missing-header");
            if (!header.equals("X-Document-Setup-Request")) request.header("X-Document-Setup-Request", "true");
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        String invalid = "{\"provider\":\"CONFLUENCE\",\"location\":{\"baseUrl\":\"https://synthetic-secret@127.0.0.1\",\"spaceId\":\"1\"},\"credentials\":{\"accountEmail\":\"synthetic@example.test\",\"apiToken\":\"synthetic-secret\"}}";
        mvc.perform(post("/api/v1/document-setup/drafts").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "http://localhost:5173").header("If-Match", "\"0\"").header("Idempotency-Key", "unsafe-url")
            .header("X-Document-Setup-Request", "true").content(invalid)).andExpect(status().isBadRequest())
            .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("synthetic")));
    }
    @Test void setupAllowedBrowserPreflightAllowsRequiredHeaders() throws Exception {
        mvc.perform(options("/api/v1/document-setup/drafts").header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type,if-match,idempotency-key,x-document-setup-request"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
