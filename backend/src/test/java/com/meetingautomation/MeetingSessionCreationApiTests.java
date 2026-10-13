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

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
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

    @Autowired
    private GlobalSettingsStore settingsStore;

    @Autowired
    private DocumentConnectionGate connectionGate;

    @Autowired
    private MeetingSessionStore sessionStore;

    @BeforeEach
    void resetMockServer() {
        mockServer.reset();
        settingsStore.transaction(current -> new GlobalSettingsStore.Change<>(
                new GlobalDocumentSettings(1, current.version() + 1,
                        new GlobalDocumentSettings.Connection("connection-fixture", 1, "NOTION",
                                java.util.Map.of(), java.util.Map.of("token", TOKEN), "session-create-root-secret",
                                "meetings-page", "participants-page", java.time.Instant.now()),
                        null, null, java.util.Map.of()), null));
        for (String sessionId : sessionStore.sessionIds()) sessionStore.remove(sessionId);
    }

    @Test
    void validRequestCreatesSessionAndReturnsServerOwnedUploadPolicy() throws Exception {
        expectRoster();

        String body = mockMvc.perform(post("/api/v1/meeting-sessions")
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
                .andExpect(jsonPath("$.data.uploadPolicy.acceptedMimeTypes[1]").value("audio/mp4"))
                .andReturn().getResponse().getContentAsString();
        String sessionId = com.jayway.jsonpath.JsonPath.read(body, "$.data.sessionId");
        MeetingSession saved = sessionStore.find(sessionId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.documentConnectionVersion()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(saved.meeting().templateVersion()).isEqualTo("1.0.0");
        org.assertj.core.api.Assertions.assertThat(saved.meeting().participantReferences())
                .containsExactly(new MeetingSession.ParticipantReference("participant-1", "Ada Lovelace"));
        org.assertj.core.api.Assertions.assertThat(saved.toString())
                .doesNotContain("Ada Lovelace", "ada@example.test");
        org.assertj.core.api.Assertions.assertThat(saved.meeting().toString())
                .doesNotContain("Ada Lovelace", "ada@example.test");
        org.assertj.core.api.Assertions.assertThat(saved.meeting().participantReferences().getFirst().toString())
                .doesNotContain("Ada Lovelace", "ada@example.test");
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("Ada Lovelace", "ada@example.test", "templateVersion");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connectionGate.beginSwitch(settingsStore.read().version()))
                .isInstanceOf(com.meetingautomation.document.settings.SettingsException.class)
                .extracting("code").isEqualTo("DOCUMENT_CHANGE_BUSY");
        mockServer.verify();
    }

    @Test
    void missingDocumentConnectionVersionIsRejectedBeforeProviderLookup() throws Exception {
        mockMvc.perform(createRequest("missing-version", validRequest().replace(
                        ",\"documentConnectionVersion\":1", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(createRequest("zero-version", validRequest().replace(
                        "\"documentConnectionVersion\":1", "\"documentConnectionVersion\":0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockServer.verify();
    }

    @Test
    void staleDocumentConnectionVersionReturnsConflictWithoutProviderLookup() throws Exception {
        mockMvc.perform(createRequest("stale-version", validRequest().replace(
                        "\"documentConnectionVersion\":1", "\"documentConnectionVersion\":2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_CONNECTION_CHANGED"));
        mockServer.verify();
    }

    @Test
    void unconfiguredConnectionReturnsSetupRequiredConflict() throws Exception {
        settingsStore.transaction(current -> new GlobalSettingsStore.Change<>(
                new GlobalDocumentSettings(1, current.version() + 1, null, null, null, java.util.Map.of()), null));
        mockMvc.perform(createRequest("unconfigured", validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_SETUP_REQUIRED"));
        mockServer.verify();
    }

    @Test
    void switchingConnectionReturnsBusyConflictWithoutProviderLookup() throws Exception {
        settingsStore.transaction(current -> new GlobalSettingsStore.Change<>(
                new GlobalDocumentSettings(1, current.version() + 1, current.active(), null,
                        new GlobalDocumentSettings.Operation("switch-1", "SWITCH", "RUNNING"),
                        java.util.Map.of()), null));
        mockMvc.perform(createRequest("switching", validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_CHANGE_BUSY"));
        mockServer.verify();
    }

    @Test
    void removingSessionReleasesSharedConnectionRegistration() throws Exception {
        expectRoster();
        String body = mockMvc.perform(createRequest("remove-session", validRequest()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = com.jayway.jsonpath.JsonPath.read(body, "$.data.sessionId");

        org.assertj.core.api.Assertions.assertThat(sessionStore.remove(sessionId)).isTrue();

        try (var switchLease = connectionGate.beginSwitch(settingsStore.read().version())) {
            org.assertj.core.api.Assertions.assertThat(switchLease).isNotNull();
        }
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
        try (var switchLease = connectionGate.beginSwitch(settingsStore.read().version())) {
            org.assertj.core.api.Assertions.assertThat(switchLease).isNotNull();
        }
        mockServer.verify();
    }

    @Test
    void transientProviderFailureAllowsSameIdempotencyKeyAndPayloadToRetry() throws Exception {
        mockServer.expect(requestTo(ROOT_URL))
                .andRespond(withServerError().body("temporary-provider-response"));

        mockMvc.perform(createRequest("transient-provider-retry-key", validRequest()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"));
        org.assertj.core.api.Assertions.assertThat(sessionStore.size()).isZero();

        mockServer.reset();
        expectRoster();
        String response = mockMvc.perform(createRequest("transient-provider-retry-key", validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();

        String sessionId = com.jayway.jsonpath.JsonPath.read(response, "$.data.sessionId");
        org.assertj.core.api.Assertions.assertThat(sessionStore.size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(sessionStore.find(sessionId)).isPresent();
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
                + "\"participantIds\":[\"participant-1\"],\"timezone\":\"Asia/Seoul\",\"documentConnectionVersion\":1,"
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
