package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionMinutesService;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.slf4j.LoggerFactory;

@SpringBootTest(properties = {"DOCUMENT_PROVIDER=", "NOTIFICATION_PROVIDER=SLACK"})
@AutoConfigureMockMvc
@Import({ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class MeetingSessionMinutesApiTests {
    private static final String SESSION_ID = "ms_minutes-api-test";
    private static final String SESSION_URL = "/api/v1/meeting-sessions/{sessionId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingSessionStore sessionStore;

    @BeforeEach
    void seedReviewSession() {
        sessionStore.remove(SESSION_ID);
        sessionStore.save(new MeetingSession(SESSION_ID, 7, "REVIEW", 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0",
                        List.of("pt_ada", "pt_grace"), "Asia/Seoul", List.of()),
                "DRAFT_READY", 100,
                List.of(new Speaker("speaker_a", "Speaker A", "pt_ada")),
                List.of(new TranscriptSegment("seg_1", "speaker_a", 100, 400, "Original transcript")), null,
                new StructuredMinutes("default.md", "1.0.0", "Old summary", List.of("Old topic"),
                        List.of("Old decision"), List.of(new StructuredMinutes.ActionItem("pt_ada", "Old task",
                                LocalDate.of(2026, 10, 20))), List.of("Old follow up"))), null);
    }

    @Test
    void validFullMinutesReplacementAdvancesVersionAndIsVisibleThroughApi010WithoutChangingTranscriptOrSpeakers()
            throws Exception {
        mockMvc.perform(updateMinutes(SESSION_ID, "7", replacement("Reviewed release readiness", "pt_grace")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8))
                .andExpect(jsonPath("$.data.minutes.templateId").value("default.md"))
                .andExpect(jsonPath("$.data.minutes.templateVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.minutes.summary").value("Reviewed release readiness"))
                .andExpect(jsonPath("$.data.minutes.discussionPoints").isArray())
                .andExpect(jsonPath("$.data.minutes.discussionPoints.length()").value(1))
                .andExpect(jsonPath("$.data.minutes.discussionPoints[0]").value("Release window"))
                .andExpect(jsonPath("$.data.minutes.decisions[0]").value("Launch on October 20"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].ownerParticipantId").value("pt_grace"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].task").value("Publish notice"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].dueDate").value("2026-10-18"))
                .andExpect(jsonPath("$.data.minutes.followUps[0]").value("Confirm deployment checklist"));

        mockMvc.perform(get(SESSION_URL, SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8))
                .andExpect(jsonPath("$.data.minutes.summary").value("Reviewed release readiness"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].ownerParticipantId").value("pt_grace"))
                .andExpect(jsonPath("$.data.speakers[0].participantId").value("pt_ada"))
                .andExpect(jsonPath("$.data.transcript[0].segmentId").value("seg_1"))
                .andExpect(jsonPath("$.data.transcript[0].text").value("Original transcript"))
                .andExpect(jsonPath("$.data.allowedActions[1]").value("UPDATE_MINUTES"));
    }

    @Test
    void identicalReplacementReturnsTheCurrentSnapshotWithoutAdvancingVersion() throws Exception {
        mockMvc.perform(updateMinutes(SESSION_ID, "7", """
                        {"templateId":"default.md","templateVersion":"1.0.0","summary":"Old summary",
                         "discussionPoints":["Old topic"],"decisions":["Old decision"],
                         "actionItems":[{"ownerParticipantId":"pt_ada","task":"Old task","dueDate":"2026-10-20"}],
                         "followUps":["Old follow up"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(7))
                .andExpect(jsonPath("$.data.minutes.summary").value("Old summary"));

        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void emptyDraftAndNullOwnerOrDueDateAreAcceptedAsCompleteMinutes() throws Exception {
        mockMvc.perform(updateMinutes(SESSION_ID, "7", """
                        {"templateId":"default.md","templateVersion":"1.0.0","summary":"",
                         "discussionPoints":[],"decisions":[],
                         "actionItems":[{"ownerParticipantId":null,"task":"Unassigned follow up","dueDate":null}],
                         "followUps":[]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8))
                .andExpect(jsonPath("$.data.minutes.summary").value(""))
                .andExpect(jsonPath("$.data.minutes.discussionPoints").isEmpty())
                .andExpect(jsonPath("$.data.minutes.actionItems[0].ownerParticipantId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].dueDate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void malformedOrIncompleteStructuredMinutesAreRejectedWithoutChangingAnySessionField() throws Exception {
        MeetingSession before = sessionStore.find(SESSION_ID).orElseThrow();
        List<String> invalidBodies = List.of(
                "null",
                "[]",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[],\"followUps\":[],\"unknown\":\"private-sentinel\"}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":null,\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":42,\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[\"   \"],\"decisions\":[],\"actionItems\":[],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[17],\"actionItems\":[],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[{\"ownerParticipantId\":\"pt_ada\",\"task\":\" \" ,\"dueDate\":null}],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[{\"ownerParticipantId\":\"pt_ada\",\"task\":\"Task\"}],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[{\"ownerParticipantId\":\"\",\"task\":\"Task\",\"dueDate\":null}],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[{\"ownerParticipantId\":41,\"task\":\"Task\",\"dueDate\":null}],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[{\"ownerParticipantId\":\"pt_ada\",\"task\":\"Task\",\"dueDate\":\"2026-02-30\"}],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[],\"followUps\":[null]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[{\"ownerParticipantId\":\"pt_ada\",\"task\":\"Task\",\"dueDate\":null,\"email\":\"private@example.com\"}],\"followUps\":[]}"
        );

        for (String body : invalidBodies) {
            MvcResult result = mockMvc.perform(updateMinutes(SESSION_ID, "7", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("private-sentinel", "private@example.com");
            assertThat(sessionStore.find(SESSION_ID).orElseThrow()).isEqualTo(before);
        }
    }

    @Test
    void templateIdentityCannotBeChangedByMinutesReplacement() throws Exception {
        for (String body : List.of(
                "{\"templateId\":\"other.md\",\"templateVersion\":\"1.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[],\"followUps\":[]}",
                "{\"templateId\":\"default.md\",\"templateVersion\":\"2.0.0\",\"summary\":\"x\",\"discussionPoints\":[],\"decisions\":[],\"actionItems\":[],\"followUps\":[]}")) {
            mockMvc.perform(updateMinutes(SESSION_ID, "7", body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(7);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().minutes().templateId()).isEqualTo("default.md");
    }

    @Test
    void actionItemOwnerMustBelongToTheSessionSelectedRoster() throws Exception {
        mockMvc.perform(updateMinutes(SESSION_ID, "7", replacement("Contains owner sentinel", "pt_external")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_NOT_FOUND"));
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(7);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().minutes().summary()).isEqualTo("Old summary");
    }

    @Test
    void acceptedTextWithSurroundingWhitespaceIsStoredWithoutRewriting() throws Exception {
        mockMvc.perform(updateMinutes(SESSION_ID, "7", """
                        {"templateId":"default.md","templateVersion":"1.0.0","summary":" Summary exactly as entered ",
                         "discussionPoints":[" Topic with surrounding spaces "],"decisions":[],
                         "actionItems":[{"ownerParticipantId":"pt_ada","task":" Task with surrounding spaces ","dueDate":null}],
                         "followUps":[" Follow up with surrounding spaces "]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minutes.summary").value(" Summary exactly as entered "))
                .andExpect(jsonPath("$.data.minutes.discussionPoints[0]").value(" Topic with surrounding spaces "))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].task").value(" Task with surrounding spaces "))
                .andExpect(jsonPath("$.data.minutes.followUps[0]").value(" Follow up with surrounding spaces "));
    }

    @Test
    void requestLogsExposeOnlySafeCodesAndCorrelationIdsForSensitivePayloads() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(MeetingSessionMinutesService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(put(SESSION_URL + "/minutes", SESSION_ID)
                            .header("If-Match", "\"7\"")
                            .header("X-Request-Id", "minutes-log-trace")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\","
                                    + "\"summary\":\"private-minutes-sentinel\",\"discussionPoints\":[],"
                                    + "\"decisions\":[],\"actionItems\":[{\"ownerParticipantId\":\"private@example.com\","
                                    + "\"task\":\"private-action-sentinel\",\"dueDate\":null}],\"followUps\":[]}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(put(SESSION_URL + "/minutes", SESSION_ID)
                            .header("If-Match", "\"7\"")
                            .header("X-Request-Id", "minutes-log-trace")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\","
                                    + "\"summary\":\"summary\",\"discussionPoints\":[],\"decisions\":[],"
                                    + "\"actionItems\":[],\"followUps\":[],"
                                    + "\"providerBody\":\"private-provider-secret-sentinel\"}"))
                    .andExpect(status().isBadRequest());
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).contains("minutes-log-trace", "MINUTES", "REJECTED",
                "PARTICIPANT_NOT_FOUND", "VALIDATION_FAILED")
                .doesNotContain("private-minutes-sentinel", "private-action-sentinel", "private@example.com",
                        "private-provider-secret-sentinel", "Original transcript");
    }

    @Test
    void sessionReviewStateAndIfMatchAreRequiredAndStaleVersionIsNotWritten() throws Exception {
        mockMvc.perform(updateMinutes("missing-session", "7", replacement("x", "pt_ada")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
        mockMvc.perform(updateMinutes(SESSION_ID, null, replacement("x", "pt_ada")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(updateMinutes(SESSION_ID, "7x", replacement("x", "pt_ada")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(updateMinutes(SESSION_ID, "6", replacement("x", "pt_ada")))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("SESSION_VERSION_CONFLICT"));
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(7);

        MeetingSession current = sessionStore.find(SESSION_ID).orElseThrow();
        sessionStore.save(new MeetingSession(current.sessionId(), current.version(), "PROCESSING",
                current.documentConnectionVersion(), current.meeting(), "TRANSCRIPTION", 50,
                current.speakers(), current.transcript(), null, current.minutes()), null);
        mockMvc.perform(updateMinutes(SESSION_ID, "7", replacement("x", "pt_ada")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(7);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().minutes().summary()).isEqualTo("Old summary");
    }

    @Test
    void concurrentRequestsWithTheSameVersionCommitAtMostOneCompleteReplacement() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> concurrentUpdate("First committed snapshot", ready, start));
            var second = workers.submit(() -> concurrentUpdate("Second committed snapshot", ready, start));
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            statuses.add(first.get(10, TimeUnit.SECONDS).getResponse().getStatus());
            statuses.add(second.get(10, TimeUnit.SECONDS).getResponse().getStatus());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 412);
        MeetingSession stored = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(stored.version()).isEqualTo(8);
        assertThat(stored.minutes().summary()).isIn("First committed snapshot", "Second committed snapshot");
        assertThat(stored.minutes().actionItems()).hasSize(1);
        assertThat(stored.transcript()).containsExactly(
                new TranscriptSegment("seg_1", "speaker_a", 100, 400, "Original transcript"));
        assertThat(stored.speakers()).containsExactly(new Speaker("speaker_a", "Speaker A", "pt_ada"));
    }

    private MvcResult concurrentUpdate(String summary, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("Concurrent requests did not start");
        return mockMvc.perform(updateMinutes(SESSION_ID, "7", replacement(summary, "pt_grace"))).andReturn();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder updateMinutes(
            String sessionId, String version, String body) {
        var request = put(SESSION_URL + "/minutes", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        return request;
    }

    private static String replacement(String summary, String ownerId) {
        return "{\"templateId\":\"default.md\",\"templateVersion\":\"1.0.0\",\"summary\":\""
                + summary + "\",\"discussionPoints\":[\"Release window\"],\"decisions\":[\"Launch on October 20\"],"
                + "\"actionItems\":[{\"ownerParticipantId\":\"" + ownerId
                + "\",\"task\":\"Publish notice\",\"dueDate\":\"2026-10-18\"}],"
                + "\"followUps\":[\"Confirm deployment checklist\"]}";
    }
}
