package com.meetingautomation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "DOCUMENT_PROVIDER=NOTION",
        "DOCUMENT_ROOT_ID=root-secret-marker",
        "NOTION_TOKEN=notion-token-marker",
        "NOTIFICATION_PROVIDER=SLACK"
})
@AutoConfigureMockMvc
@Import({SelectedDocumentProviderApiTests.MockProviderHttpConfiguration.class,
        ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class SpeakerMappingApiTests {
    private static final String SESSION_ID = "ms_speaker-mapping-api-test";
    private static final String URL = "/api/v1/meeting-sessions/{sessionId}/speaker-mappings";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingSessionStore sessionStore;

    @Autowired
    private MockRestServiceServer mockServer;

    @BeforeEach
    void createReviewSession() {
        mockServer.reset();
        sessionStore.remove(SESSION_ID);
        saveSession("REVIEW", 11, List.of(new Speaker("speaker_a", "Speaker A")));
    }

    @Test
    void replacesAllSpeakerMappingsAndReturnsTheNewVersionAndParticipantName() throws Exception {
        expectRoster(List.of(new RosterEntry("pt_ada", "Ada Lovelace", "ada@example.com")));
        mockMvc.perform(put(URL, SESSION_ID)
                        .header("If-Match", "\"11\"")
                        .header("X-Request-Id", "speaker-mapping-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mappings":[{"speakerId":"speaker_a","participantId":"pt_ada"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(12))
                .andExpect(jsonPath("$.data.mappings[0].speakerId").value("speaker_a"))
                .andExpect(jsonPath("$.data.mappings[0].participantId").value("pt_ada"))
                .andExpect(jsonPath("$.data.mappings[0].participantName").value("Ada Lovelace"));

        MeetingSession updated = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(updated.version()).isEqualTo(12);
        assertThat(updated.speakers()).containsExactly(new Speaker("speaker_a", "Speaker A", "pt_ada"));
        assertThat(updated.transcript()).containsExactly(
                new TranscriptSegment("seg_001", "speaker_a", 100, 400, "discussion text"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/meeting-sessions/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(12))
                .andExpect(jsonPath("$.data.speakers[0].participantId").value("pt_ada"))
                .andExpect(jsonPath("$.data.transcript[0].speakerId").value("speaker_a"))
                .andExpect(jsonPath("$.data.transcript[0].text").value("discussion text"))
                .andExpect(jsonPath("$.data.allowedActions").value(org.hamcrest.Matchers.hasItem("UPDATE_SPEAKER_MAPPING")));
    }

    @Test
    void explicitNullClearsOneMappingWhileOtherMappingsAndSpeakerOrderAreRetained() throws Exception {
        saveSession("REVIEW", 11, List.of(new Speaker("speaker_a", "Speaker A", "pt_grace"),
                new Speaker("speaker_b", "Speaker B", "pt_ada")));
        expectRoster(List.of(new RosterEntry("pt_ada", "Ada Lovelace", "ada@example.com")));

        mockMvc.perform(put(URL, SESSION_ID).header("If-Match", "\"11\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappings\":[{\"speakerId\":\"speaker_a\",\"participantId\":null},{\"speakerId\":\"speaker_b\",\"participantId\":\"pt_ada\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mappings[0].speakerId").value("speaker_a"))
                .andExpect(jsonPath("$.data.mappings[0].participantId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.mappings[0].participantName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.mappings[1].speakerId").value("speaker_b"))
                .andExpect(jsonPath("$.data.mappings[1].participantId").value("pt_ada"));

        assertThat(sessionStore.find(SESSION_ID).orElseThrow().speakers())
                .containsExactly(new Speaker("speaker_a", "Speaker A", null),
                        new Speaker("speaker_b", "Speaker B", "pt_ada"));
    }

    @Test
    void emptySpeakerSetAcceptsEmptyMappingsAndAdvancesVersion() throws Exception {
        saveSession("REVIEW", 11, List.of());
        mockMvc.perform(put(URL, SESSION_ID).header("If-Match", "\"11\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mappings\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(12))
                .andExpect(jsonPath("$.data.mappings").isEmpty());
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().speakers()).isEmpty();
    }

    @Test
    void rejectsIncompleteDuplicateAndUnknownSpeakerSetsWithoutChangingSession() throws Exception {
        String before = sessionStore.find(SESSION_ID).orElseThrow().toString();
        for (String body : List.of(
                "{\"mappings\":[]}",
                "{\"mappings\":[{\"speakerId\":\"speaker_a\",\"participantId\":null},{\"speakerId\":\"speaker_a\",\"participantId\":null}]}",
                "{\"mappings\":[{\"speakerId\":\"unknown\",\"participantId\":null}]}",
                "{\"mappings\":[null]}",
                "{\"mappings\":[{\"speakerId\":null,\"participantId\":null}]}",
                "{\"mappings\":[{\"speakerId\":\"speaker_a\"}]}",
                "{\"mappings\":[{\"speakerId\":\"speaker_a\",\"participantId\":17}]}")) {
            mockMvc.perform(put(URL, SESSION_ID).header("If-Match", "\"11\"")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        MeetingSession unchanged = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(11);
        assertThat(unchanged.toString()).isEqualTo(before);
    }

    @Test
    void rejectsParticipantOutsideSelectedRosterWithoutProviderLookupOrMutation() throws Exception {
        mockMvc.perform(put(URL, SESSION_ID).header("If-Match", "\"11\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappings\":[{\"speakerId\":\"speaker_a\",\"participantId\":\"pt_outside\"}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_NOT_FOUND"));
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(11);
    }

    @Test
    void missingSessionReturnsNotFoundEnvelope() throws Exception {
        mockMvc.perform(put(URL, "ms_missing-speaker-session").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappings\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void selectedParticipantMissingFromCurrentProviderRosterReturnsNotFoundWithoutMutation() throws Exception {
        expectRoster(List.of());
        mockMvc.perform(put(URL, SESSION_ID).header("If-Match", "\"11\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappings\":[{\"speakerId\":\"speaker_a\",\"participantId\":\"pt_ada\"}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_NOT_FOUND"));
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(11);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().speakers())
                .containsExactly(new Speaker("speaker_a", "Speaker A"));
    }

    @Test
    void providerRosterFailureIsNormalizedAndLeavesMappingsUntouched() throws Exception {
        mockServer.expect(MockRestRequestMatchers.requestTo("https://api.notion.com/v1/blocks/root-secret-marker/children"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withServerError().body("private-provider-error-sentinel"));
        String body = mockMvc.perform(put(URL, SESSION_ID).header("If-Match", "\"11\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappings\":[{\"speakerId\":\"speaker_a\",\"participantId\":\"pt_ada\"}]}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private-provider-error-sentinel", "notion-token-marker", "ada@example.com");
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(11);
    }

    @Test
    void validatesSessionStateAndIfMatchBeforeApplyingAnyChange() throws Exception {
        for (String invalidHeader : List.of("11", "W/\"11\"", "\"abc\"")) {
            mockMvc.perform(put(URL, SESSION_ID).header("If-Match", invalidHeader)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"mappings\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        String completeMappings = "{\"mappings\":[{\"speakerId\":\"speaker_a\",\"participantId\":null}]}";
        mockMvc.perform(put(URL, SESSION_ID).header("If-Match", "\"10\"")
                        .contentType(MediaType.APPLICATION_JSON).content(completeMappings))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("SESSION_VERSION_CONFLICT"));
        saveSession("PROCESSING", 10, List.of(new Speaker("speaker_a", "Speaker A")));
        mockMvc.perform(put(URL, SESSION_ID).header("If-Match", "\"9\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mappings\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(10);

        mockMvc.perform(put(URL, "ms_missing-speaker-session").header("If-Match", "bad")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
        saveSession("REVIEW", 10, List.of(new Speaker("speaker_a", "Speaker A")));
        mockMvc.perform(put(URL, SESSION_ID).contentType(MediaType.APPLICATION_JSON).content(completeMappings))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private void saveSession(String state, int version, List<Speaker> speakers) {
        sessionStore.save(new MeetingSession(SESSION_ID, version, state, 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0",
                        List.of("pt_ada", "pt_grace"), "Asia/Seoul", List.of()),
                "REVIEW".equals(state) ? "DRAFT_READY" : "TRANSCRIPTION", 100,
                speakers, List.of(new TranscriptSegment("seg_001", "speaker_a", 100, 400, "discussion text")),
                null, new StructuredMinutes("default.md", "1.0.0", "Summary", List.of(), List.of(), List.of(), List.of())),
                null);
    }

    private void expectRoster(List<RosterEntry> entries) {
        mockServer.expect(MockRestRequestMatchers.requestTo("https://api.notion.com/v1/blocks/root-secret-marker/children"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"has_more":false,"next_cursor":null,"results":[
                        {"id":"meetings-page","type":"child_page","child_page":{"title":"Meetings"}},
                        {"id":"participants-page","type":"child_page","child_page":{"title":"Participants"}}]}
                        """, MediaType.APPLICATION_JSON));
        String rosterRows = entries.stream().map(entry -> "{\"id\":\"" + entry.id()
                + "\",\"type\":\"child_page\",\"child_page\":{\"title\":\"" + entry.name() + "\"}}")
                .collect(java.util.stream.Collectors.joining(","));
        mockServer.expect(MockRestRequestMatchers.requestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":[" + rosterRows + "]}", MediaType.APPLICATION_JSON));
        for (RosterEntry entry : entries) {
            mockServer.expect(MockRestRequestMatchers.requestTo("https://api.notion.com/v1/blocks/" + entry.id() + "/children"))
                    .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                    .andRespond(MockRestResponseCreators.withSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":[{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"plain_text\":\"Email: " + entry.email() + "\"}]}}]}", MediaType.APPLICATION_JSON));
        }
    }

    private record RosterEntry(String id, String name, String email) { }
}
