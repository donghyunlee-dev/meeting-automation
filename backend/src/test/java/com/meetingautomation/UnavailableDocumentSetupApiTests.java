package com.meetingautomation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"DOCUMENT_SETTINGS_DIR=", "DOCUMENT_SETTINGS_ENCRYPTION_KEY=", "DOCUMENT_PROVIDER=NOTION",
    "NOTION_TOKEN=ignored-secret", "ALLOWED_ORIGINS=http://localhost:5173"})
@AutoConfigureMockMvc
@Import(ConstraintViolationProbeService.class)
class UnavailableDocumentSetupApiTests {
    @Autowired MockMvc mvc;
    @Test void setupStorageUnavailableIsDistinctFromEmptyAndBlocksSavingAndBusinessAccess() throws Exception {
        mvc.perform(get("/api/v1/document-setup")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("STORAGE_UNAVAILABLE"))
            .andExpect(jsonPath("$.data.version").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(header().doesNotExist("ETag"));
        mvc.perform(get("/api/v1/app-config")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.setup.status").value("STORAGE_UNAVAILABLE"))
            .andExpect(jsonPath("$.data.document.provider").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/participants")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("DOCUMENT_SETTINGS_UNAVAILABLE"));
        mvc.perform(post("/api/v1/document-setup/drafts").contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "http://localhost:5173").header("If-Match", "\"0\"").header("Idempotency-Key", "save")
            .header("X-Document-Setup-Request", "true")
            .content("{\"provider\":\"NOTION\",\"location\":{\"parentPageId\":\"aaaaaaaabbbbccccddddeeeeeeeeeeee\"},\"credentials\":{\"token\":\"synthetic-secret\"}}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.category").value("INTERNAL"));
    }
}
