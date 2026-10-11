package com.meetingautomation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "DOCUMENT_PROVIDER=NOTIONN")
@AutoConfigureMockMvc
@Import({ErrorContractProbeController.class, ConstraintViolationProbeService.class, UnconfiguredSelectedProviderApiTests.EmptySettings.class})
class InvalidDocumentProviderApiTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void legacySelectorDoesNotSelectProviderFromAppConfig() throws Exception {
        mockMvc.perform(get("/api/v1/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.provider").value(org.hamcrest.Matchers.nullValue()))

                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("NOTIONN")));
    }

    @Test
    void legacySelectorDoesNotSelectProviderFromIntegrationHealth() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.provider").value(org.hamcrest.Matchers.nullValue()))

                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("NOTIONN")));
    }

    @Test
    void legacySelectorCannotEnableParticipantsWithoutActiveSettings() throws Exception {
        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_SETUP_REQUIRED"))

                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("NOTIONN")));
    }
}
