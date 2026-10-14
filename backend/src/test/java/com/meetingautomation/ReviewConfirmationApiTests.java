package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"DOCUMENT_PROVIDER=", "NOTIFICATION_PROVIDER=SLACK", "OPENAI_API_KEY="})
@AutoConfigureMockMvc
@Import({ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class ReviewConfirmationApiTests {
    private static final String SESSION_URL = "/api/v1/meeting-sessions/{sessionId}";
    private static final String CONFIRM_URL = SESSION_URL + "/confirm";
    private static final String VALID_MINUTES_SENTINEL = "private-minutes-sentinel";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingSessionStore sessionStore;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private DocumentProviderResolver documentProviderResolver;

    @BeforeEach
    void clearProviderInteractions() {
        clearInvocations(documentProviderResolver);
    }

    @Test
    void validReviewConfirmsAtomicallyAndApi010RemovesReviewActions() throws Exception {
        String sessionId = saveReview("REVIEW", 7, validSpeakers(), validMinutes());

        mockMvc.perform(confirm(sessionId, "7", "confirm-valid-1", "{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.version").value(8));

        MeetingSession confirmed = sessionStore.find(sessionId).orElseThrow();
        assertThat(confirmed.version()).isEqualTo(8);
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.speakers()).isEqualTo(validSpeakers());
        assertThat(confirmed.minutes()).isEqualTo(validMinutes());
        mockMvc.perform(get(SESSION_URL, sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.version").value(8))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("PUBLISH"))
                .andExpect(jsonPath("$.data.allowedActions.length()").value(1));
        assertNoExternalProviderCalls();
    }

    @Test
    void successfulIdempotencyReplayReturnsTheOriginalResponseWithoutRevalidatingOrMutatingAgain()
            throws Exception {
        String sessionId = saveReview("REVIEW", 7, validSpeakers(), validMinutes());

        String first = mockMvc.perform(confirm(sessionId, "7", "confirm-replay", "{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        MeetingSession terminal = sessionStore.find(sessionId).orElseThrow();
        String replay = mockMvc.perform(confirm(sessionId, "7", "confirm-replay", "{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(sessionStore.find(sessionId).orElseThrow()).isEqualTo(terminal);
        assertThat(terminal.version()).isEqualTo(8);
        assertNoExternalProviderCalls();
    }

    @Test
    void reusingIdempotencyKeyForAnotherSessionConflictsAndDoesNotChangeEitherSession() throws Exception {
        String firstId = saveReview("REVIEW", 7, validSpeakers(), validMinutes());
        String secondId = saveReview("REVIEW", 3, validSpeakers(), validMinutes());
        MeetingSession firstBefore = sessionStore.find(firstId).orElseThrow();
        MeetingSession secondBefore = sessionStore.find(secondId).orElseThrow();
        mockMvc.perform(confirm(firstId, "7", "confirm-cross-session", "{\"confirm\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(confirm(secondId, "3", "confirm-cross-session", "{\"confirm\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertThat(sessionStore.find(firstId).orElseThrow().version()).isEqualTo(firstBefore.version() + 1);
        assertThat(sessionStore.find(secondId).orElseThrow()).isEqualTo(secondBefore);
        assertNoExternalProviderCalls();
    }

    @Test
    void newKeyAfterConfirmationConflictsWithoutAnotherVersionChange() throws Exception {
        String sessionId = saveReview("REVIEW", 7, validSpeakers(), validMinutes());
        mockMvc.perform(confirm(sessionId, "7", "confirm-once", "{\"confirm\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(confirm(sessionId, "8", "confirm-new-key", "{\"confirm\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));

        assertThat(sessionStore.find(sessionId).orElseThrow().version()).isEqualTo(8);
        assertThat(sessionStore.find(sessionId).orElseThrow().status()).isEqualTo("CONFIRMED");
        assertNoExternalProviderCalls();
    }

    @Test
    void allSpeakerAndMinutesIssuesAreReturnedInDeterministicOrderWithoutMutatingSnapshot() throws Exception {
        List<Speaker> speakers = List.of(
                new Speaker("speaker_1", "private-speaker-name", null),
                new Speaker("speaker_2", "private-second-name", "outside-roster"));
        StructuredMinutes minutes = new StructuredMinutes(null, null, null,
                List.of("   "), List.of("private-decision-sentinel"),
                List.of(new StructuredMinutes.ActionItem("outside-owner", " ", LocalDate.of(2026, 2, 28))),
                List.of("\t"));
        String sessionId = saveReview("REVIEW", 7, speakers, minutes);
        MeetingSession before = sessionStore.find(sessionId).orElseThrow();

        MvcResult first = mockMvc.perform(confirm(sessionId, "7", "confirm-incomplete-1", "{\"confirm\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REVIEW_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.category").value("VALIDATION"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andReturn();
        MvcResult second = mockMvc.perform(confirm(sessionId, "7", "confirm-incomplete-2", "{\"confirm\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        JsonNode firstIssues = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("error").path("details").path("issues");
        JsonNode secondIssues = objectMapper.readTree(second.getResponse().getContentAsString())
                .path("error").path("details").path("issues");
        assertThat(firstIssues).isEqualTo(secondIssues);
        assertThat(firstIssues.toString()).isEqualTo("""
                [{"path":"speakers[0].participantId","code":"SPEAKER_UNMAPPED"},
                 {"path":"speakers[1].participantId","code":"PARTICIPANT_OUTSIDE_SESSION_ROSTER"},
                 {"path":"minutes.templateId","code":"REQUIRED"},
                 {"path":"minutes.templateVersion","code":"REQUIRED"},
                 {"path":"minutes.summary","code":"REQUIRED"},
                 {"path":"minutes.discussionPoints[0]","code":"BLANK_VALUE"},
                 {"path":"minutes.actionItems[0].ownerParticipantId","code":"PARTICIPANT_OUTSIDE_SESSION_ROSTER"},
                 {"path":"minutes.actionItems[0].task","code":"BLANK_VALUE"},
                 {"path":"minutes.followUps[0]","code":"BLANK_VALUE"}]
                """.replace("\n", "").replace(" ", ""));
        assertThat(first.getResponse().getContentAsString())
                .doesNotContain("private-speaker-name", "private-second-name", "private-decision-sentinel",
                        "outside-owner", "private-minutes-sentinel");
        assertThat(sessionStore.find(sessionId).orElseThrow()).isEqualTo(before);
        assertNoExternalProviderCalls();
    }

    @Test
    void emptySpeakerAndMinutesSectionsAndBlankSummaryAreValid() throws Exception {
        StructuredMinutes minutes = new StructuredMinutes("default.md", "1.0.0", "",
                List.of(), List.of(), List.of(), List.of());
        String sessionId = saveReview("REVIEW", 2, List.of(), minutes);

        mockMvc.perform(confirm(sessionId, "2", "confirm-empty", "{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.version").value(3));
        assertNoExternalProviderCalls();
    }

    @Test
    void nullOptionalOwnerAndDueDateAreValidWhenAllRequiredFieldsArePresent() throws Exception {
        StructuredMinutes minutes = new StructuredMinutes("default.md", "1.0.0", "",
                List.of(), List.of(),
                List.of(new StructuredMinutes.ActionItem(null, "Follow up", null)), List.of());
        String sessionId = saveReview("REVIEW", 5, List.of(), minutes);

        mockMvc.perform(confirm(sessionId, "5", "confirm-null-options", "{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertNoExternalProviderCalls();
    }

    @Test
    void blankItemsAndExternalActionOwnerAggregate() throws Exception {
        StructuredMinutes minutes = new StructuredMinutes("default.md", "1.0.0", "Summary",
                List.of(), List.of(" \t "),
                List.of(new StructuredMinutes.ActionItem("outside-roster", " ", LocalDate.of(2026, 2, 1))),
                List.of());
        String sessionId = saveReview("REVIEW", 4, List.of(), minutes);

        mockMvc.perform(confirm(sessionId, "4", "confirm-minutes-issues", "{\"confirm\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REVIEW_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.issues[0].path").value("minutes.decisions[0]"))
                .andExpect(jsonPath("$.error.details.issues[0].code").value("BLANK_VALUE"))
                .andExpect(jsonPath("$.error.details.issues[1].path").value("minutes.actionItems[0].ownerParticipantId"))
                .andExpect(jsonPath("$.error.details.issues[1].code").value("PARTICIPANT_OUTSIDE_SESSION_ROSTER"))
                .andExpect(jsonPath("$.error.details.issues[2].path").value("minutes.actionItems[0].task"))
                .andExpect(jsonPath("$.error.details.issues[2].code").value("BLANK_VALUE"));
        assertNoExternalProviderCalls();
    }

    @Test
    void malformedPersistedMinutesElementReturnsInvalidTypeWithoutMutatingReview() throws Exception {
        @SuppressWarnings("unchecked")
        List<String> malformedDiscussionPoints = (List<String>) (List<?>) List.of(42);
        StructuredMinutes minutes = new StructuredMinutes("default.md", "1.0.0", "Summary",
                malformedDiscussionPoints, List.of(), List.of(), List.of());
        String sessionId = saveReview("REVIEW", 6, List.of(), minutes);
        MeetingSession before = sessionStore.find(sessionId).orElseThrow();

        mockMvc.perform(confirm(sessionId, "6", "confirm-invalid-element", "{\"confirm\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REVIEW_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.issues[0].path").value("minutes.discussionPoints[0]"))
                .andExpect(jsonPath("$.error.details.issues[0].code").value("INVALID_TYPE"));

        assertThat(sessionStore.find(sessionId).orElseThrow()).isEqualTo(before);
        assertNoExternalProviderCalls();
    }

    @Test
    void mismatchingMinutesTemplateProvenanceReturnsBothIssuesWithoutMutation() throws Exception {
        StructuredMinutes minutes = new StructuredMinutes("different-template.md", "2.0.0", "Summary",
                List.of(), List.of(), List.of(), List.of());
        String sessionId = saveReview("REVIEW", 6, List.of(), minutes);
        MeetingSession before = sessionStore.find(sessionId).orElseThrow();

        MvcResult result = mockMvc.perform(confirm(sessionId, "6", "confirm-template-mismatch", "{\"confirm\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REVIEW_VALIDATION_FAILED"))
                .andReturn();

        JsonNode issues = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("error").path("details").path("issues");
        assertThat(issues.toString()).isEqualTo("""
                [{"path":"minutes.templateId","code":"TEMPLATE_MISMATCH"},
                 {"path":"minutes.templateVersion","code":"TEMPLATE_MISMATCH"}]
                """.replace("\n", "").replace(" ", ""));
        assertThat(sessionStore.find(sessionId).orElseThrow()).isEqualTo(before);
        assertNoExternalProviderCalls();
    }

    @Test
    void missingPersistedMinutesAndRosterExternalSpeakerAreReportedWithoutPartialTransition() throws Exception {
        String sessionId = saveReview("REVIEW", 9,
                List.of(new Speaker("speaker_1", "Speaker", "outside-roster")), null);
        MeetingSession before = sessionStore.find(sessionId).orElseThrow();

        mockMvc.perform(confirm(sessionId, "9", "confirm-missing-minutes", "{\"confirm\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.issues[0].path").value("speakers[0].participantId"))
                .andExpect(jsonPath("$.error.details.issues[0].code").value("PARTICIPANT_OUTSIDE_SESSION_ROSTER"))
                .andExpect(jsonPath("$.error.details.issues[1].path").value("minutes"))
                .andExpect(jsonPath("$.error.details.issues[1].code").value("REQUIRED"));
        assertThat(sessionStore.find(sessionId).orElseThrow()).isEqualTo(before);
        assertNoExternalProviderCalls();
    }

    @Test
    void malformedConfirmBodiesAre400AndDoNotChangeReviewSnapshot() throws Exception {
        List<String> bodies = List.of("", "{", "{}", "{\"confirm\":null}", "{\"confirm\":false}",
                "{\"confirm\":\"true\"}", "{\"confirm\":1}", "[]", "{\"confirm\":true,\"secret\":\"body-sentinel\"}");
        for (String body : bodies) {
            String sessionId = saveReview("REVIEW", 7, validSpeakers(), validMinutes());
            MeetingSession before = sessionStore.find(sessionId).orElseThrow();
            mockMvc.perform(confirm(sessionId, "7", "confirm-malformed-" + UUID.randomUUID(), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
            assertThat(sessionStore.find(sessionId).orElseThrow()).isEqualTo(before);
        }
        assertNoExternalProviderCalls();
    }

    @Test
    void missingOrMalformedHeadersAre400AndLeaveSessionUnchanged() throws Exception {
        String sessionId = saveReview("REVIEW", 7, validSpeakers(), validMinutes());
        MeetingSession before = sessionStore.find(sessionId).orElseThrow();
        List<MvcResult> results = new ArrayList<>();
        results.add(mockMvc.perform(post(CONFIRM_URL, sessionId).header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}")).andReturn());
        results.add(mockMvc.perform(post(CONFIRM_URL, sessionId).header("Idempotency-Key", "confirm-bad-header")
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}")).andReturn());
        results.add(mockMvc.perform(confirm(sessionId, "7", " ", "{\"confirm\":true}")).andReturn());
        results.add(mockMvc.perform(confirm(sessionId, "7", "bad/header", "{\"confirm\":true}")).andReturn());
        results.add(mockMvc.perform(confirm(sessionId, "7x", "confirm-bad-if-match", "{\"confirm\":true}")).andReturn());
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        }
        assertThat(sessionStore.find(sessionId).orElseThrow()).isEqualTo(before);
        assertNoExternalProviderCalls();
    }

    @Test
    void unknownSessionReturns404BeforeAnyMutation() throws Exception {
        mockMvc.perform(confirm("ms_confirmation_missing", "1", "confirm-missing-session", "{\"confirm\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
        assertNoExternalProviderCalls();
    }

    @Test
    void nonReviewStateAndStaleIfMatchHaveDistinctConflictsAndPreserveSnapshot() throws Exception {
        String processingId = saveReview("PROCESSING", 7, validSpeakers(), validMinutes());
        MeetingSession processingBefore = sessionStore.find(processingId).orElseThrow();
        mockMvc.perform(confirm(processingId, "7", "confirm-processing", "{\"confirm\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        assertThat(sessionStore.find(processingId).orElseThrow()).isEqualTo(processingBefore);

        String reviewId = saveReview("REVIEW", 7, validSpeakers(), validMinutes());
        MeetingSession reviewBefore = sessionStore.find(reviewId).orElseThrow();
        mockMvc.perform(confirm(reviewId, "6", "confirm-stale", "{\"confirm\":true}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("SESSION_VERSION_CONFLICT"));
        assertThat(sessionStore.find(reviewId).orElseThrow()).isEqualTo(reviewBefore);
        assertNoExternalProviderCalls();
    }

    @Test
    void concurrentDifferentKeysAtSameVersionCommitOnlyOneConfirmation() throws Exception {
        String sessionId = saveReview("REVIEW", 7, validSpeakers(), validMinutes());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> confirmTogether(
                    ready, start, sessionId, "confirm-race-a"));
            Future<MvcResult> second = executor.submit(() -> confirmTogether(
                    ready, start, sessionId, "confirm-race-b"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
                    second.get(10, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(sessionStore.find(sessionId).orElseThrow().status()).isEqualTo("CONFIRMED");
        assertThat(sessionStore.find(sessionId).orElseThrow().version()).isEqualTo(8);
        assertNoExternalProviderCalls();
    }

    private MvcResult confirmTogether(CountDownLatch ready, CountDownLatch start, String sessionId, String key)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Confirm race did not start.");
        return mockMvc.perform(confirm(sessionId, "7", key, "{\"confirm\":true}")).andReturn();
    }

    private String saveReview(String status, int version, List<Speaker> speakers, StructuredMinutes minutes) {
        String sessionId = "ms_review-confirm-" + UUID.randomUUID();
        MeetingSession session = new MeetingSession(sessionId, version, status, 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0", List.of("pt_ada", "pt_grace"),
                        "Asia/Seoul", List.of(new MeetingSession.ParticipantReference("pt_ada", "Ada Private"),
                                new MeetingSession.ParticipantReference("pt_grace", "Grace Private"))),
                "DRAFT_READY", 100, speakers,
                speakers.isEmpty() ? List.of() : List.of(new TranscriptSegment("seg_private",
                        speakers.getFirst().speakerId(), 100, 400, "private-transcript-sentinel")), null, minutes);
        sessionStore.save(session, null);
        return sessionId;
    }

    private static List<Speaker> validSpeakers() {
        return List.of(new Speaker("speaker_1", "Speaker one", "pt_ada"));
    }

    private static StructuredMinutes validMinutes() {
        return new StructuredMinutes("default.md", "1.0.0", VALID_MINUTES_SENTINEL,
                List.of("Discussion"), List.of("Decision"),
                List.of(new StructuredMinutes.ActionItem("pt_grace", "Publish notice", LocalDate.of(2026, 10, 20))),
                List.of("Follow up"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(
            String sessionId, String version, String key, String body) {
        return post(CONFIRM_URL, sessionId)
                .header("If-Match", "\"" + version + "\"")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void assertNoExternalProviderCalls() {
        verifyNoInteractions(documentProviderResolver);
    }
}
