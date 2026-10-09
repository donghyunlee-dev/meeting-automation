package com.meetingautomation;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "DOCUMENT_PROVIDER=NOTION",
        "DOCUMENT_ROOT_ID=session-create-root-secret",
        "NOTION_TOKEN=session-create-notion-token-secret",
        "NOTIFICATION_PROVIDER=SLACK"
})
@AutoConfigureMockMvc
@Import({SelectedDocumentProviderApiTests.MockProviderHttpConfiguration.class,
        ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class MeetingSessionCreationApiTests {
    private static final String TOKEN = "session-create-notion-token-secret";
    private static final String ROOT_URL = "https://api.notion.com/v1/blocks/session-create-root-secret/children";
    private static final String ROSTER_URL = "https://api.notion.com/v1/blocks/participants-page/children";
    private static final String PARTICIPANT_URL = "https://api.notion.com/v1/blocks/participant-1/children";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockRestServiceServer mockServer;

    @BeforeEach
    void resetMockServer() {
        mockServer.reset();
    }

    @Test
    void validRequestCreatesSessionAndReturnsServerOwnedUploadPolicy() throws Exception {
        expectRoster();

        mockMvc.perform(post("/api/v1/meeting-sessions")
                        .header("Idempotency-Key", "session-create-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").isString())
                .andExpect(jsonPath("$.data.sessionId").value(org.hamcrest.Matchers.startsWith("ms_")))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.uploadPolicy.chunkDurationSeconds").value(15))
                .andExpect(jsonPath("$.data.uploadPolicy.maxChunkBytes").value(5242880))
                .andExpect(jsonPath("$.data.uploadPolicy.acceptedMimeTypes[0]").value("audio/webm"))
                .andExpect(jsonPath("$.data.uploadPolicy.acceptedMimeTypes[1]").value("audio/mp4"));
        mockServer.verify();
    }

    @Test
    void matchingIdempotencyKeyReplaysCreatedSessionWithoutAnotherProviderLookup() throws Exception {
        expectRoster();

        String first = mockMvc.perform(createRequest("replay-key", validRequest()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(createRequest("replay-key", validRequest()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
        org.assertj.core.api.Assertions.assertThat(first).doesNotContain("browser-recovery-key");
        mockServer.verify();
    }

    @Test
    void sameIdempotencyKeyWithDifferentNormalizedPayloadReturnsConflict() throws Exception {
        expectRoster();
        mockMvc.perform(createRequest("conflict-key", validRequest())).andExpect(status().isCreated());

        mockMvc.perform(createRequest("conflict-key", validRequest().replace("Sprint Planning", "Retrospective")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        mockServer.verify();
    }

    @Test
    void invalidTimezoneAndMissingIdempotencyKeyFailBeforeProviderLookup() throws Exception {
        mockMvc.perform(createRequest("validation-key", validRequest().replace("Asia/Seoul", "UTC+09:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/meeting-sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockServer.verify();
    }

    @Test
    void unknownParticipantReturnsValidationErrorWithoutLeakingIdentifiers() throws Exception {
        expectRoster();

        mockMvc.perform(createRequest("unknown-participant-key",
                        validRequest().replace("participant-1", "not-in-roster-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("not-in-roster-secret"))));
        mockServer.verify();
    }

    @Test
    void providerFailureReturnsSafeGatewayErrorWithoutSavingSession() throws Exception {
        mockServer.expect(requestTo(ROOT_URL))
                .andRespond(withServerError().body("provider-secret-response"));

        mockMvc.perform(createRequest("provider-failure-key", validRequest()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("provider-secret-response"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(TOKEN))));
        mockServer.verify();
    }

    @Test
    void missingParticipantStructureReturns422WithoutSavingSession() throws Exception {
        mockServer.expect(requestTo(ROOT_URL))
                .andRespond(withSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":[]}",
                        MediaType.APPLICATION_JSON));

        mockMvc.perform(createRequest("structure-failure-key", validRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_STRUCTURE_NOT_FOUND"));
        mockServer.verify();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createRequest(
            String key, String body) {
        return post("/api/v1/meeting-sessions")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void expectRoster() {
        mockServer.expect(requestTo(ROOT_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(requiredChildren(), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(ROSTER_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(rosterChild(), MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(PARTICIPANT_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(participantBody(), MediaType.APPLICATION_JSON));
    }

    private static String validRequest() {
        return "{\"title\":\"  Sprint Planning  \",\"templateId\":\"default.md\","
                + "\"participantIds\":[\"participant-1\"],\"timezone\":\"Asia/Seoul\","
                + "\"recoveryKey\":\"browser-recovery-key\"}";
    }

    private static String requiredChildren() {
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":["
                + "{\"id\":\"meetings-page\",\"type\":\"child_page\","
                + "\"child_page\":{\"title\":\"Meetings\"}},"
                + "{\"id\":\"participants-page\",\"type\":\"child_page\","
                + "\"child_page\":{\"title\":\"Participants\"}}]}";
    }

    private static String rosterChild() {
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":["
                + "{\"id\":\"participant-1\",\"type\":\"child_page\","
                + "\"child_page\":{\"title\":\"Ada Lovelace\"}}]}";
    }

    private static String participantBody() {
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":["
                + "{\"id\":\"email-block\",\"type\":\"paragraph\",\"paragraph\":{"
                + "\"rich_text\":[{\"plain_text\":\"Email: ada@example.test\"}]}}]}";
    }
}
