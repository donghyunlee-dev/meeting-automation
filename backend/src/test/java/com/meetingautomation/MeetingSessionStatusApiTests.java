package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.MeetingSessionStatusResponse;
import com.meetingautomation.api.session.MeetingSessionStatusService;
import com.meetingautomation.api.session.ProcessingFailure;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.time.LocalDate;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"DOCUMENT_PROVIDER=", "NOTIFICATION_PROVIDER=SLACK"})
@AutoConfigureMockMvc
@Import({ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class MeetingSessionStatusApiTests {
    private static final String SESSION_ID = "ms_status-api-test";
    private static final String STATUS_URL = "/api/v1/meeting-sessions/{sessionId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingSessionStore sessionStore;

    @Autowired
    private MeetingSessionStatusService statusService;

    @BeforeEach
    void createCreatedSession() {
        sessionStore.remove(SESSION_ID);
        sessionStore.save(new MeetingSession(SESSION_ID, 1, "CREATED", 1,
                new MeetingSession.Meeting("Planning", "default.md", List.of("participant-1"), "Asia/Seoul")),
                null);
    }

    @Test
    void createdAndUploadingSessionsExposeAnEmptyProcessingObject() throws Exception {
        mockMvc.perform(get(STATUS_URL, SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.processing").isMap())
                .andExpect(jsonPath("$.data.processing.stage").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.processing.progressPercent").value(0));

        sessionStore.save(new MeetingSession(SESSION_ID, 2, "UPLOADING", 1,
                new MeetingSession.Meeting("Planning", "default.md", List.of("participant-1"), "Asia/Seoul")),
                null);

        mockMvc.perform(get(STATUS_URL, SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPLOADING"))
                .andExpect(jsonPath("$.data.processing").isMap())
                .andExpect(jsonPath("$.data.processing.stage").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.processing.progressPercent").value(0))
                .andExpect(jsonPath("$.data.speakers").isEmpty())
                .andExpect(jsonPath("$.data.transcript").isEmpty())
                .andExpect(jsonPath("$.data.minutes").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.allowedActions").isEmpty());
    }

    @Test
    void reviewReturnsCompleteReviewSnapshotWithTimeOrderedTranscriptAndCurrentActions() throws Exception {
        StructuredMinutes minutes = new StructuredMinutes("default.md", "1.0.0", "Decide launch date",
                List.of("Review schedule"), List.of("Launch in October"),
                List.of(new StructuredMinutes.ActionItem("participant-1", "Send release plan",
                        LocalDate.of(2026, 10, 20))), List.of("Check readiness"));
        MeetingSession review = new MeetingSession(SESSION_ID, 7, "REVIEW", 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0", List.of("participant-1"),
                        "Asia/Seoul", List.of()),
                "DRAFT_READY", 100,
                List.of(new Speaker("speaker_a", "Speaker A", "participant-1")),
                List.of(new TranscriptSegment("seg_2", "speaker_a", 1200, 1800, "Later discussion"),
                        new TranscriptSegment("seg_1", "speaker_a", 300, 900, "Opening discussion")),
                null, minutes);
        sessionStore.save(review, null);

        mockMvc.perform(get(STATUS_URL, SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(7))
                .andExpect(jsonPath("$.data.status").value("REVIEW"))
                .andExpect(jsonPath("$.data.processing.stage").value("DRAFT_READY"))
                .andExpect(jsonPath("$.data.processing.progressPercent").value(100))
                .andExpect(jsonPath("$.data.speakers[0].speakerId").value("speaker_a"))
                .andExpect(jsonPath("$.data.speakers[0].label").value("Speaker A"))
                .andExpect(jsonPath("$.data.speakers[0].participantId").value("participant-1"))
                .andExpect(jsonPath("$.data.transcript[0].segmentId").value("seg_1"))
                .andExpect(jsonPath("$.data.transcript[0].startMs").value(300))
                .andExpect(jsonPath("$.data.transcript[0].text").value("Opening discussion"))
                .andExpect(jsonPath("$.data.transcript[1].segmentId").value("seg_2"))
                .andExpect(jsonPath("$.data.minutes.templateId").value("default.md"))
                .andExpect(jsonPath("$.data.minutes.templateVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.minutes.summary").value("Decide launch date"))
                .andExpect(jsonPath("$.data.minutes.discussionPoints[0]").value("Review schedule"))
                .andExpect(jsonPath("$.data.minutes.decisions[0]").value("Launch in October"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].ownerParticipantId").value("participant-1"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].task").value("Send release plan"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].dueDate").value("2026-10-20"))
                .andExpect(jsonPath("$.data.minutes.followUps[0]").value("Check readiness"))
                .andExpect(jsonPath("$.data.allowedActions").isArray())
                .andExpect(jsonPath("$.data.allowedActions.length()").value(4))
                .andExpect(jsonPath("$.data.allowedActions[0]").value("UPDATE_SPEAKER_MAPPING"))
                .andExpect(jsonPath("$.data.allowedActions[1]").value("UPDATE_MINUTES"))
                .andExpect(jsonPath("$.data.allowedActions[2]").value("REGENERATE_MINUTES"))
                .andExpect(jsonPath("$.data.allowedActions[3]").value("CONFIRM"));

        MeetingSessionStatusResponse response = statusService.get(SESSION_ID);
        assertThat(response.toString()).doesNotContain("Opening discussion", "Later discussion");
    }

    @Test
    void processingFailureHidesReviewContentAndSensitiveText() throws Exception {
        MeetingSession failed = new MeetingSession(SESSION_ID, 9, "PROCESSING_FAILED", 1,
                new MeetingSession.Meeting("Planning", "default.md", List.of("participant-1"), "Asia/Seoul"),
                "TRANSCRIPTION", 34,
                List.of(new Speaker("speaker_a", "Speaker A")),
                List.of(new TranscriptSegment("seg_1", "speaker_a", 0, 1000, "private-transcript-sentinel")),
                new ProcessingFailure("TRANSCRIPTION", "PROVIDER_ERROR", true),
                new StructuredMinutes("default.md", "1.0.0", "private-minutes-sentinel",
                        List.of("private-provider-detail-sentinel"), List.of(), List.of(), List.of()));
        sessionStore.save(failed, null);

        String body = mockMvc.perform(get(STATUS_URL, SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING_FAILED"))
                .andExpect(jsonPath("$.data.processing.stage").value("TRANSCRIPTION"))
                .andExpect(jsonPath("$.data.processing.progressPercent").value(34))
                .andExpect(jsonPath("$.data.processing.errorCode").value("PROVIDER_ERROR"))
                .andExpect(jsonPath("$.data.processing.retryable").value(true))
                .andExpect(jsonPath("$.data.processing.audioAvailable").value(false))
                .andExpect(jsonPath("$.data.processing.audioExpiresAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.speakers").isEmpty())
                .andExpect(jsonPath("$.data.transcript").isEmpty())
                .andExpect(jsonPath("$.data.minutes").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.allowedActions").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("private-transcript-sentinel", "private-minutes-sentinel",
                "private-provider-detail-sentinel");
        assertThat(statusService.get(SESSION_ID).toString()).doesNotContain("private-transcript-sentinel",
                "private-minutes-sentinel", "private-provider-detail-sentinel");
    }

    @Test
    void repeatedGetReturnsTheSameSnapshotWithoutChangingSessionState() throws Exception {
        sessionStore.save(new MeetingSession(SESSION_ID, 4, "PROCESSING", 1,
                new MeetingSession.Meeting("Planning", "default.md", List.of("participant-1"), "Asia/Seoul"),
                "TRANSCRIPTION", 34, List.of(), List.of(), null), null);

        MvcResult first = mockMvc.perform(get(STATUS_URL, SESSION_ID))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(get(STATUS_URL, SESSION_ID))
                .andExpect(status().isOk()).andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        MeetingSession stored = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(stored.version()).isEqualTo(4);
        assertThat(stored.status()).isEqualTo("PROCESSING");
        assertThat(stored.processingStage()).isEqualTo("TRANSCRIPTION");
        assertThat(stored.progressPercent()).isEqualTo(34);
    }

    @Test
    void missingSessionReturnsSessionNotFoundEnvelope() throws Exception {
        mockMvc.perform(get(STATUS_URL, "ms_missing-status-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.error.category").value("NOT_FOUND"));
    }
}
