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

@SpringBootTest(properties = {
        "DOCUMENT_PROVIDER=NOTION",
        "DOCUMENT_ROOT_ID=root-public-marker",
        "NOTION_TOKEN=",
        "NOTIFICATION_PROVIDER=SLACK"
})
@AutoConfigureMockMvc
@Import({SelectedDocumentProviderApiTests.MockProviderHttpConfiguration.class,
        ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class UnconfiguredSelectedProviderApiTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void selectedProviderWithMissingCredentialRemainsSelectedAndUnconfigured() throws Exception {
        mockMvc.perform(get("/api/v1/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.provider").value("NOTION"))
                .andExpect(jsonPath("$.data.document.configured").value(false))
                .andExpect(result -> {
                    String response = result.getResponse().getContentAsString();
                    assertFalse(response.contains("root-public-marker"));
                    assertFalse(response.contains("NOTION_TOKEN"));
                });
    }
}
