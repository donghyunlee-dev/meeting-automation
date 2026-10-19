package com.meetingautomation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.document.settings.GlobalSettingsStore;
import com.meetingautomation.document.settings.InMemoryGlobalSettingsStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"RENDER=true", "ALLOWED_ORIGINS=http://localhost:5173"})
@AutoConfigureMockMvc
@Import(ConstraintViolationProbeService.class)
class RenderDocumentSetupStartupTests {
    @Autowired MockMvc mvc;
    @Autowired GlobalSettingsStore settingsStore;

    @Test
    void startsWithAnEmptyRuntimeStoreInsteadOfReportingMissingDiskStorage() throws Exception {
        assertThat(settingsStore).isInstanceOf(InMemoryGlobalSettingsStore.class);
        mvc.perform(get("/api/v1/document-setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.data.canCreateMeeting").value(false));
    }
}
