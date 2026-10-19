package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.document.settings.GlobalSettingsStore;
import com.meetingautomation.document.settings.InMemoryGlobalSettingsStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"RENDER=false", "DOCUMENT_SETTINGS_DIR=", "DOCUMENT_SETTINGS_ENCRYPTION_KEY=",
        "ALLOWED_ORIGINS=http://localhost:5173"})
@AutoConfigureMockMvc
@Import(ConstraintViolationProbeService.class)
class LocalDocumentSetupStartupTests {
    @Autowired MockMvc mvc;
    @Autowired GlobalSettingsStore settingsStore;

    @Test
    void startsUnconfiguredWhenLocalFileSettingsWereNotConfigured() throws Exception {
        assertThat(settingsStore).isInstanceOf(InMemoryGlobalSettingsStore.class);
        mvc.perform(get("/api/v1/document-setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNCONFIGURED"));
    }
}
