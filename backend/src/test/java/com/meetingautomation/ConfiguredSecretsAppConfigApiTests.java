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
        "DOCUMENT_ROOT_ID=app-config-root-secret-marker",
        "NOTION_TOKEN=app-config-notion-token-secret-marker",
        "EMAIL_PROVIDER=GMAIL_API",
        "EMAIL_OAUTH_CLIENT_ID=app-config-email-client-id-marker",
        "EMAIL_OAUTH_CLIENT_SECRET=app-config-email-client-secret-marker",
        "EMAIL_OAUTH_REFRESH_TOKEN=app-config-email-refresh-token-marker",
        "EMAIL_SENDER_ADDRESS=automation@example.test",
        "NOTIFICATION_PROVIDER=SLACK"
})
@AutoConfigureMockMvc
@Import(SelectedDocumentProviderApiTests.MockProviderHttpConfiguration.class)
class ConfiguredSecretsAppConfigApiTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void configuredProvidersExposeStatusWithoutCredentialValues() throws Exception {
        mockMvc.perform(get("/api/v1/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.provider").value("NOTION"))
                .andExpect(jsonPath("$.data.document.configured").value(true))
                .andExpect(jsonPath("$.data.email.enabled").value(true))
                .andExpect(jsonPath("$.data.email.configured").value(true))
                .andExpect(result -> {
                    String response = result.getResponse().getContentAsString();
                    assertFalse(response.contains("app-config-root-secret-marker"));
                    assertFalse(response.contains("app-config-notion-token-secret-marker"));
                    assertFalse(response.contains("app-config-email-client-id-marker"));
                    assertFalse(response.contains("app-config-email-client-secret-marker"));
                    assertFalse(response.contains("app-config-email-refresh-token-marker"));
                });
    }
}
