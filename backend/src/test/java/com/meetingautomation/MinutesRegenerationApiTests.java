package com.meetingautomation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.MeetingSessionStatusResponse;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.minutes.GroundedMinutesResult;
import com.meetingautomation.minutes.MinutesCommand;
import com.meetingautomation.minutes.MinutesGenerationProvider;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"DOCUMENT_PROVIDER=", "NOTIFICATION_PROVIDER=SLACK", "OPENAI_API_KEY="})
@AutoConfigureMockMvc
@Import({ConstraintViolationProbeService.class, MinutesRegenerationApiTests.TestProviderConfiguration.class})
class MinutesRegenerationApiTests {
    private static final String SESSION_ID = "ms_minutes-regeneration-api";
    private static final String URL = "/api/v1/meeting-sessions/{sessionId}/minutes/regenerate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingSessionStore sessionStore;

    @Autowired
    private TestMinutesGenerationProvider provider;

    @BeforeEach
    void createReviewSession() {
        sessionStore.remove(SESSION_ID);
        provider.reset();
        sessionStore.save(new MeetingSession(SESSION_ID, 7, "REVIEW", 1,
                new MeetingSession.Meeting("Planning", "default.md", "1.0.0", List.of("participant-1"),
                        "Asia/Seoul", List.of(new MeetingSession.ParticipantReference("participant-1", "Ari"))),
                "DRAFT_READY", 100,
                List.of(new Speaker("speaker_a", "Speaker A", "participant-1")),
                List.of(new TranscriptSegment("seg_1", "speaker_a", 0, 1200, "Discuss launch")),
                null,
                new StructuredMinutes("default.md", "1.0.0", "Existing summary",
                        List.of("Existing discussion"), List.of("Existing decision"), List.of(), List.of("Existing follow-up"))),
                null);
    }

    @Test
    void validRequestAcceptsRegenerationAndReturnsProcessingContract() throws Exception {
        String key = newIdempotencyKey();
        mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.stage").value("DRAFT_REGENERATION"))
                .andExpect(jsonPath("$.data.templateId").value("project.md"));

        MeetingSession completed = awaitTerminalOperation();
        assertThat(completed.version()).isGreaterThan(7);
        assertThat(completed.status()).isEqualTo("REVIEW");
        assertThat(completed.processingStage()).isEqualTo("DRAFT_READY");
        assertThat(completed.progressPercent()).isEqualTo(100);
        assertThat(completed.meeting().templateId()).isEqualTo("project.md");
        assertThat(completed.minutes()).isEqualTo(new StructuredMinutes(
                "project.md", "1.0.0", "Generated summary", List.of(), List.of(), List.of(), List.of()));
        assertThat(completed.transcript()).isEqualTo(List.of(
                new TranscriptSegment("seg_1", "speaker_a", 0, 1200, "Discuss launch")));
        assertThat(sessionStore.findLastOperation(SESSION_ID).orElseThrow().outcome()).isEqualTo("SUCCEEDED");
        mockMvc.perform(get("/api/v1/meeting-sessions/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastOperation.type").value("MINUTES_REGENERATION"))
                .andExpect(jsonPath("$.data.lastOperation.outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.lastOperation.requestedTemplateId").value("project.md"))
                .andExpect(jsonPath("$.data.lastOperation.errorCode").doesNotExist());
        MinutesCommand command = provider.command.get();
        assertThat(command.templateId()).isEqualTo("project.md");
        assertThat(command.templateVersion()).isEqualTo("1.0.0");
        assertThat(command.templateContent()).isNotBlank();
        assertThat(command.speakerParticipantMappings()).containsEntry("speaker_a", "participant-1");
        assertThat(command.participants()).containsExactly(
                new MeetingSession.ParticipantReference("participant-1", "Ari"));
    }

    @Test
    void emptyTranscriptCreatesTemplateSpecificDraftWithoutCallingProvider() throws Exception {
        setTranscript(List.of());
        mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", newIdempotencyKey())
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isAccepted());

        MeetingSession completed = awaitTerminalOperation();
        assertThat(completed.minutes()).isEqualTo(new StructuredMinutes(
                "project.md", "1.0.0", "", List.of(), List.of(), List.of(), List.of()));
        assertThat(provider.command.get()).isNull();
    }

    @Test
    void providerFailureRestoresTheCompletePreviousReviewSnapshot() throws Exception {
        MeetingSession before = sessionStore.find(SESSION_ID).orElseThrow();
        provider.fail.set(true);
        mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", newIdempotencyKey())
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isAccepted());

        MeetingSession restored = awaitTerminalOperation();
        assertThat(restored.version()).isGreaterThan(7);
        assertThat(restored.status()).isEqualTo("REVIEW");
        assertThat(restored.meeting()).isEqualTo(before.meeting());
        assertThat(restored.minutes()).isEqualTo(before.minutes());
        assertThat(restored.speakers()).isEqualTo(before.speakers());
        assertThat(restored.transcript()).isEqualTo(before.transcript());
        MeetingSessionStatusResponse.LastOperation operation =
                sessionStore.findLastOperation(SESSION_ID).orElseThrow();
        assertThat(operation.type()).isEqualTo("MINUTES_REGENERATION");
        assertThat(operation.outcome()).isEqualTo("FAILED");
        assertThat(operation.requestedTemplateId()).isEqualTo("project.md");
        assertThat(operation.errorCode()).isEqualTo("MINUTES_REGENERATION_FAILED");

        String statusBody = mockMvc.perform(get("/api/v1/meeting-sessions/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastOperation.outcome").value("FAILED"))
                .andExpect(jsonPath("$.data.lastOperation.requestedTemplateId").value("project.md"))
                .andExpect(jsonPath("$.data.lastOperation.errorCode").value("MINUTES_REGENERATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(statusBody).doesNotContain("provider-secret-sentinel");
    }

    @Test
    void invalidTemplateIsRejectedBeforeChangingTheReviewSession() throws Exception {
        mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", newIdempotencyKey())
                        .contentType("application/json")
                        .content("{\"templateId\":\"missing.md\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        MeetingSession unchanged = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(7);
        assertThat(unchanged.status()).isEqualTo("REVIEW");
        assertThat(unchanged.minutes().summary()).isEqualTo("Existing summary");
    }

    @Test
    void staleVersionAndMissingSessionAreRejectedWithoutStartingGeneration() throws Exception {
        mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"6\"")
                        .header("Idempotency-Key", newIdempotencyKey())
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.code").value("SESSION_VERSION_CONFLICT"));
        mockMvc.perform(post(URL, "ms_regeneration_missing")
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", newIdempotencyKey())
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(7);
        assertThat(provider.command.get()).isNull();
    }

    @Test
    void reusedIdempotencyKeyWithDifferentTemplateReturnsConflictWithoutAnotherMutation() throws Exception {
        String key = newIdempotencyKey();
        mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"templateId\":\"default.md\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        mockMvc.perform(post(URL, "ms_regeneration_missing")
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isGreaterThan(7);
    }

    @Test
    void staleOperationFailureCannotOverwriteANewerRegeneration() {
        sessionStore.startMinutesRegeneration(SESSION_ID, 7, "operation-old", "project.md");
        sessionStore.completeMinutesRegeneration(SESSION_ID, "operation-old", "project.md", "1.0.0",
                new StructuredMinutes("project.md", "1.0.0", "Generated", List.of(), List.of(), List.of(), List.of()));
        sessionStore.startMinutesRegeneration(SESSION_ID, 9, "operation-new", "default.md");

        assertThatThrownBy(() -> sessionStore.failMinutesRegeneration(SESSION_ID, "operation-old"))
                .isInstanceOf(com.meetingautomation.api.session.SessionStateConflictException.class);
        MeetingSession active = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(active.version()).isEqualTo(10);
        assertThat(active.status()).isEqualTo("PROCESSING");
        assertThat(active.processingStage()).isEqualTo("DRAFT_REGENERATION");
        assertThat(sessionStore.isActiveMinutesRegeneration(SESSION_ID, "operation-new")).isTrue();
    }

    @Test
    void identicalIdempotencyReplayReturnsTheOriginalAcceptanceWithoutChangingTheSnapshotAgain() throws Exception {
        setTranscript(List.of());
        String key = newIdempotencyKey();
        String first = mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        awaitTerminalOperation();
        int terminalVersion = sessionStore.find(SESSION_ID).orElseThrow().version();

        String replay = mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "\"7\"")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().version()).isEqualTo(terminalVersion);
        assertThat(provider.command.get()).isNull();
    }

    @Test
    void missingOrMalformedPreconditionHeadersAreRejectedWithoutMutation() throws Exception {
        mockMvc.perform(post(URL, SESSION_ID)
                        .header("Idempotency-Key", newIdempotencyKey())
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(URL, SESSION_ID)
                        .header("If-Match", "7")
                        .header("Idempotency-Key", " ")
                        .contentType("application/json")
                        .content("{\"templateId\":\"project.md\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        MeetingSession unchanged = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(7);
        assertThat(unchanged.status()).isEqualTo("REVIEW");
        assertThat(provider.command.get()).isNull();
    }

    private void setTranscript(List<TranscriptSegment> transcript) {
        MeetingSession current = sessionStore.find(SESSION_ID).orElseThrow();
        sessionStore.save(new MeetingSession(current.sessionId(), current.version(), current.status(),
                current.documentConnectionVersion(), current.meeting(), current.processingStage(),
                current.progressPercent(), current.speakers(), transcript, current.processingFailure(), current.minutes()), null);
    }

    private MeetingSession awaitTerminalOperation() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            MeetingSession current = sessionStore.find(SESSION_ID).orElseThrow();
            if ("REVIEW".equals(current.status()) && sessionStore.findLastOperation(SESSION_ID).isPresent()) {
                return current;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Minutes regeneration did not reach a terminal Review snapshot.");
    }

    private static String newIdempotencyKey() {
        return "regenerate-" + UUID.randomUUID();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProviderConfiguration {
        @Bean
        @Primary
        TestMinutesGenerationProvider regenerationTestProvider() {
            return new TestMinutesGenerationProvider();
        }
    }

    static class TestMinutesGenerationProvider implements MinutesGenerationProvider {
        private final AtomicReference<MinutesCommand> command = new AtomicReference<>();
        private final AtomicBoolean fail = new AtomicBoolean();

        @Override
        public GroundedMinutesResult generateMinutes(MinutesCommand input) {
            command.set(input);
            if (fail.get()) throw new IllegalStateException("provider-secret-sentinel");
            String segmentId = input.transcript().getFirst().segmentId();
            return new GroundedMinutesResult(input.templateId(), input.templateVersion(),
                    List.of(new GroundedMinutesResult.TextClaim("Generated summary", List.of(segmentId))),
                    List.of(), List.of(), List.of(), List.of());
        }

        void reset() {
            command.set(null);
            fail.set(false);
        }
    }
}

