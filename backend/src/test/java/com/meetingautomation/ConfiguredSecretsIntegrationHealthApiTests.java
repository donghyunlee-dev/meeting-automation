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
        "DOCUMENT_PROVIDER=",
        "DOCUMENT_ROOT_ID=health-root-id-secret-marker",
        "NOTION_TOKEN=health-notion-token-secret-marker",
        "CONFLUENCE_BASE_URL=https://confluence.example.test",
        "CONFLUENCE_ACCOUNT_EMAIL=health-confluence-email-marker@example.test",
        "CONFLUENCE_API_TOKEN=health-confluence-token-secret-marker",
        "EMAIL_PROVIDER=",
        "EMAIL_OAUTH_CLIENT_ID=health-email-client-id-marker",
        "EMAIL_OAUTH_CLIENT_SECRET=health-email-client-secret-marker",
        "EMAIL_OAUTH_REFRESH_TOKEN=health-email-refresh-token-marker",
        "EMAIL_SENDER_ADDRESS=health-sender-marker@example.test",
        "OPENAI_API_KEY=health-openai-api-key-marker",
        "NOTIFICATION_PROVIDER=",
        "SLACK_MEETING_WEBHOOK_URL=https://hooks.slack.com/services/T-HEALTH/B-MEETING/health-meeting-webhook-marker",
        "SLACK_ADMIN_WEBHOOK_URL=https://hooks.slack.com/services/T-HEALTH/B-ADMIN/health-admin-webhook-marker"
})
@AutoConfigureMockMvc
@Import(ConstraintViolationProbeService.class)
class ConfiguredSecretsIntegrationHealthApiTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void integrationHealthSerializesOnlyStatusFieldsWhenRuntimeSecretCanariesArePresent() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.configured").value(false))
                .andExpect(jsonPath("$.data.document.reachable").value(false))
                .andExpect(jsonPath("$.data.document.rootAccessible").value(false))
                .andExpect(jsonPath("$.data.document.rootId").doesNotExist())
                .andExpect(jsonPath("$.data.email.configured").value(false))
                .andExpect(jsonPath("$.data.email.reachable").value(false))
                .andExpect(jsonPath("$.data.email.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.notification.provider").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.notification.configured").value(false))
                .andExpect(jsonPath("$.data.notification.reachable").value(false))
                .andExpect(jsonPath("$.data.notification.webhookUrl").doesNotExist())
                .andExpect(jsonPath("$.data.ai.configured").value(false))
                .andExpect(jsonPath("$.data.ai.reachable").value(false))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("health-root-id-secret-marker"));
                    assertFalse(body.contains("health-notion-token-secret-marker"));
                    assertFalse(body.contains("health-confluence-email-marker@example.test"));
                    assertFalse(body.contains("health-confluence-token-secret-marker"));
                    assertFalse(body.contains("health-email-client-id-marker"));
                    assertFalse(body.contains("health-email-client-secret-marker"));
                    assertFalse(body.contains("health-email-refresh-token-marker"));
                    assertFalse(body.contains("health-sender-marker@example.test"));
                    assertFalse(body.contains("health-openai-api-key-marker"));
                    assertFalse(body.contains("health-meeting-webhook-marker"));
                    assertFalse(body.contains("health-admin-webhook-marker"));
                });
    }
}
