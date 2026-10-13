package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.UploadPolicy;
import com.meetingautomation.api.processing.ProcessingJob;
import com.meetingautomation.api.processing.ProcessingJobQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "DOCUMENT_PROVIDER=",
        "NOTIFICATION_PROVIDER=SLACK",
        "MAX_CHUNK_BYTES=64",
        "MAX_MEETING_DURATION_MINUTES=1",
        "ACCEPTED_AUDIO_MIME_TYPES=audio/webm,audio/mp4"
})
@AutoConfigureMockMvc
@Import({ProcessingStartApiTests.QueueFixture.class,
        ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class ProcessingStartApiTests {
    private static final String SESSION_ID = "ms_processing-start-api-test";
    private static final Path AUDIO_ROOT = createTemporaryDirectory("meeting-processing-audio-test-");
    private static final Path SETTINGS_ROOT = createTemporaryDirectory("meeting-processing-settings-test-");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingSessionStore sessionStore;

    @Autowired
    private ControllableProcessingQueue processingExecutor;

    @DynamicPropertySource
    static void registerDirectories(DynamicPropertyRegistry registry) {
        registry.add("TEMP_AUDIO_DIR", AUDIO_ROOT::toString);
        registry.add("DOCUMENT_SETTINGS_DIR", SETTINGS_ROOT::toString);
        registry.add("DOCUMENT_SETTINGS_ENCRYPTION_KEY",
                () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @BeforeEach
    void createSessionAndResetQueue() throws IOException {
        sessionStore.remove(SESSION_ID);
        deleteRecursively(AUDIO_ROOT);
        Files.createDirectories(AUDIO_ROOT);
        processingExecutor.reset();
        sessionStore.save(new MeetingSession(SESSION_ID, 1, "CREATED", 1,
                new MeetingSession.Meeting("Planning", "default.md", List.of("participant-1"), "Asia/Seoul")),
                null, new UploadPolicy(15, 64, List.of("audio/webm", "audio/mp4")));
    }

    @Test
    void firstDurableChunkMovesSessionToUploadingAndLaterChunksKeepThatVersion() throws Exception {
        upload("0", new byte[] {1, 2, 3}, "first").andExpect(status().isOk());
        MeetingSession afterFirst = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(afterFirst.status()).isEqualTo("UPLOADING");
        assertThat(afterFirst.version()).isEqualTo(2);

        upload("1", new byte[] {4, 5}, "second").andExpect(status().isOk());
        MeetingSession afterSecond = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(afterSecond.status()).isEqualTo("UPLOADING");
        assertThat(afterSecond.version()).isEqualTo(2);
        mockMvc.perform(get("/api/v1/meeting-sessions/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPLOADING"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.meeting.participantIds[0]").value("participant-1"))
                .andExpect(jsonPath("$.data.speakers").isArray())
                .andExpect(jsonPath("$.data.transcript").isArray())
                .andExpect(jsonPath("$.data.allowedActions").isArray());
    }

    @Test
    void invalidChunkLeavesSessionCreatedAtItsOriginalVersion() throws Exception {
        byte[] bytes = new byte[] {6, 7};
        upload("0", bytes, "bad-mime", "audio/ogg")
                .andExpect(status().isBadRequest());

        MeetingSession unchanged = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(unchanged.status()).isEqualTo("CREATED");
        assertThat(unchanged.version()).isEqualTo(1);
    }

    @Test
    void storageFailureLeavesSessionCreatedAtItsOriginalVersion() throws Exception {
        deleteRecursively(AUDIO_ROOT);
        Files.writeString(AUDIO_ROOT, "regular file blocks the audio storage root");

        upload("0", new byte[] {16, 17}, "storage-failure")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        MeetingSession unchanged = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(unchanged.status()).isEqualTo("CREATED");
        assertThat(unchanged.version()).isEqualTo(1);
    }

    @Test
    void completeUploadStartsProcessingAndSessionStatusExposesAudioAssemblyStage() throws Exception {
        upload("0", new byte[] {8, 9}, "start-processing").andExpect(status().isOk());

        mockMvc.perform(process("start-processing-key", "{\"expectedChunks\":1,\"durationMs\":15000,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.stage").value("AUDIO_ASSEMBLY"));

        mockMvc.perform(get("/api/v1/meeting-sessions/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.processing.stage").value("AUDIO_ASSEMBLY"))
                .andExpect(jsonPath("$.data.processing.progressPercent").value(0));
        assertThat(processingExecutor.acceptedCount()).isEqualTo(1);
        assertThat(processingExecutor.jobs()).containsExactly(
                new ProcessingJob(SESSION_ID, 1, 15000, "audio/webm"));
    }

    @Test
    void newProcessingRequestFromCreatedSessionConflictsWithoutQueueing() throws Exception {
        mockMvc.perform(process("created-session-key", "{\"expectedChunks\":1,\"durationMs\":15000,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        assertThat(processingExecutor.acceptedCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("CREATED");
    }

    @Test
    void incompleteSequencesDoNotCreateProcessingJob() throws Exception {
        upload("0", new byte[] {10}, "chunk-zero").andExpect(status().isOk());

        mockMvc.perform(process("incomplete-key", "{\"expectedChunks\":2,\"durationMs\":30000,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNKS_INCOMPLETE"));
        assertThat(processingExecutor.acceptedCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("UPLOADING");
    }

    @Test
    void extraSequenceAndExpectedCountMismatchDoNotCreateProcessingJob() throws Exception {
        upload("0", new byte[] {18}, "extra-zero").andExpect(status().isOk());
        upload("1", new byte[] {19}, "extra-one").andExpect(status().isOk());

        mockMvc.perform(process("extra-sequence-key", "{\"expectedChunks\":1,\"durationMs\":15000,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNKS_INCOMPLETE"));
        assertThat(processingExecutor.acceptedCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("UPLOADING");
    }

    @Test
    void missingSequenceDoesNotCreateProcessingJob() throws Exception {
        upload("0", new byte[] {20}, "gap-zero").andExpect(status().isOk());
        upload("2", new byte[] {21}, "gap-two").andExpect(status().isOk());

        mockMvc.perform(process("gap-key", "{\"expectedChunks\":2,\"durationMs\":30000,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNKS_INCOMPLETE"));
        assertThat(processingExecutor.acceptedCount()).isZero();
    }

    @Test
    void processingRequestForMissingSessionReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/meeting-sessions/{sessionId}/process", "ms_missing-processing-session")
                        .header("Idempotency-Key", "missing-session-key")
                        .contentType("application/json")
                        .content("{\"expectedChunks\":1,\"durationMs\":15000,\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void fractionalExpectedChunkCountIsRejectedBeforeQueueing() throws Exception {
        upload("0", new byte[] {11}, "fractional-count-upload").andExpect(status().isOk());

        mockMvc.perform(process("fractional-count-key", "{\"expectedChunks\":1.9,\"durationMs\":15000,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(processingExecutor.acceptedCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("UPLOADING");
    }

    @Test
    void fractionalDurationIsRejectedBeforeQueueing() throws Exception {
        upload("0", new byte[] {11}, "fractional-duration-upload").andExpect(status().isOk());

        mockMvc.perform(process("fractional-duration-key", "{\"expectedChunks\":1,\"durationMs\":15000.9,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(processingExecutor.acceptedCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("UPLOADING");
    }

    @Test
    void stringExpectedChunkCountIsRejectedBeforeQueueing() throws Exception {
        upload("0", new byte[] {11}, "string-count-upload").andExpect(status().isOk());

        mockMvc.perform(process("string-count-key", "{\"expectedChunks\":\"1\",\"durationMs\":15000,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(processingExecutor.acceptedCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("UPLOADING");
    }

    @Test
    void stringDurationIsRejectedBeforeQueueing() throws Exception {
        upload("0", new byte[] {11}, "string-duration-upload").andExpect(status().isOk());

        mockMvc.perform(process("string-duration-key", "{\"expectedChunks\":1,\"durationMs\":\"15000\","
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(processingExecutor.acceptedCount()).isZero();
        assertThat(sessionStore.find(SESSION_ID).orElseThrow().status()).isEqualTo("UPLOADING");
    }

    @Test
    void durationMimeAndIdempotencyKeyAreValidatedBeforeQueueing() throws Exception {
        upload("0", new byte[] {11}, "valid-upload").andExpect(status().isOk());

        mockMvc.perform(process("duration-zero", "{\"expectedChunks\":1,\"durationMs\":0,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(process("count-zero", "{\"expectedChunks\":0,\"durationMs\":15000,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(process("duration-too-long", "{\"expectedChunks\":1,\"durationMs\":60001,"
                        + "\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(process("unsupported-mime", "{\"expectedChunks\":1,\"durationMs\":15000,"
                        + "\"mimeType\":\"audio/ogg\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/meeting-sessions/{sessionId}/process", SESSION_ID)
                        .contentType("application/json")
                        .content("{\"expectedChunks\":1,\"durationMs\":15000,\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/meeting-sessions/{sessionId}/process", SESSION_ID)
                        .header("Idempotency-Key", " ")
                        .contentType("application/json")
                        .content("{\"expectedChunks\":1,\"durationMs\":15000,\"mimeType\":\"audio/webm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(process("missing-mime", "{\"expectedChunks\":1,\"durationMs\":15000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(process("malformed-body", "{\"expectedChunks\":,"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(processingExecutor.acceptedCount()).isZero();
    }

    @Test
    void matchingProcessingRequestReplaysAfterStateTransitionAndConflictingPayloadIsRejected() throws Exception {
        upload("0", new byte[] {12}, "idem-upload").andExpect(status().isOk());
        String body = "{\"expectedChunks\":1,\"durationMs\":15000,\"mimeType\":\"audio/webm\"}";
        String first = mockMvc.perform(process("stable-processing-key", body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(process("stable-processing-key", body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(processingExecutor.acceptedCount()).isEqualTo(1);
        mockMvc.perform(process("stable-processing-key", body.replace("15000", "14000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        mockMvc.perform(process("new-key-after-processing", body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_STATE_CONFLICT"));
        assertThat(processingExecutor.acceptedCount()).isEqualTo(1);
    }

    @Test
    void concurrentMatchingProcessingRequestsEnqueueExactlyOneJob() throws Exception {
        upload("0", new byte[] {22}, "concurrent-idem-upload").andExpect(status().isOk());
        String body = "{\"expectedChunks\":1,\"durationMs\":15000,\"mimeType\":\"audio/webm\"}";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> processTogether(ready, start, "same-concurrent-key", body));
            Future<MvcResult> second = executor.submit(() -> processTogether(ready, start, "same-concurrent-key", body));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult firstResult = first.get(5, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(202, 202);
            assertThat(firstResult.getResponse().getContentAsString())
                    .isEqualTo(secondResult.getResponse().getContentAsString());
        }
        assertThat(processingExecutor.acceptedCount()).isEqualTo(1);
    }

    @Test
    void queueFailureLeavesUploadingSessionUnchangedAndSameKeyCanRetry() throws Exception {
        upload("0", new byte[] {13}, "queue-failure-upload").andExpect(status().isOk());
        processingExecutor.rejectNext();
        String body = "{\"expectedChunks\":1,\"durationMs\":15000,\"mimeType\":\"audio/webm\"}";

        mockMvc.perform(process("queue-failure-key", body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("test queue rejection"));
        MeetingSession unchanged = sessionStore.find(SESSION_ID).orElseThrow();
        assertThat(unchanged.status()).isEqualTo("UPLOADING");
        assertThat(unchanged.version()).isEqualTo(2);
        assertThat(processingExecutor.acceptedCount()).isZero();

        mockMvc.perform(process("queue-failure-key", body)).andExpect(status().isAccepted());
        assertThat(processingExecutor.acceptedCount()).isEqualTo(1);
    }

    @Test
    void uploadAndProcessingStartSerializeSoNoChunkPersistsAfterProcessingBegins() throws Exception {
        upload("0", new byte[] {14}, "race-initial-upload").andExpect(status().isOk());
        processingExecutor.blockNextExecution();
        String processBody = "{\"expectedChunks\":1,\"durationMs\":15000,\"mimeType\":\"audio/webm\"}";
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> processResult = executor.submit(() -> mockMvc.perform(
                    process("race-processing-key", processBody)).andReturn());
            assertThat(processingExecutor.awaitBlocked(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch uploadRequestStarted = new CountDownLatch(1);
            Future<MvcResult> uploadResult = executor.submit(() -> {
                uploadRequestStarted.countDown();
                return upload("1", new byte[] {15}, "race-upload").andReturn();
            });
            assertThat(uploadRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> uploadResult.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            processingExecutor.releaseBlockedExecution();
            assertThat(processResult.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(202);
            MvcResult rejectedUpload = uploadResult.get(5, TimeUnit.SECONDS);
            assertThat(rejectedUpload.getResponse().getStatus()).isEqualTo(409);
            assertThat(rejectedUpload.getResponse().getContentAsString()).contains("SESSION_STATE_CONFLICT");
        }

        mockMvc.perform(get("/api/v1/meeting-sessions/{sessionId}/audio", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedSequences.length()").value(1));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder process(String key, String body) {
        return post("/api/v1/meeting-sessions/{sessionId}/process", SESSION_ID)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body);
    }

    private MvcResult processTogether(CountDownLatch ready, CountDownLatch start, String key, String body)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent request did not start.");
        return mockMvc.perform(process(key, body)).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions upload(String sequence, byte[] bytes, String key)
            throws Exception {
        return upload(sequence, bytes, key, "audio/webm");
    }

    private org.springframework.test.web.servlet.ResultActions upload(
            String sequence, byte[] bytes, String key, String mimeType) throws Exception {
        return mockMvc.perform(put("/api/v1/meeting-sessions/{sessionId}/audio/chunks/{sequence}", SESSION_ID,
                        sequence)
                .contentType(mimeType)
                .header("X-Audio-SHA256", sha256(bytes))
                .header("X-Audio-Byte-Length", bytes.length)
                .header("Idempotency-Key", key)
                .content(bytes));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path createTemporaryDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class QueueFixture {
        @Bean
        @Primary
        ControllableProcessingQueue processingExecutor() {
            return new ControllableProcessingQueue();
        }
    }

    static final class ControllableProcessingQueue implements ProcessingJobQueue {
        private final AtomicInteger accepted = new AtomicInteger();
        private final ConcurrentLinkedQueue<ProcessingJob> jobs = new ConcurrentLinkedQueue<>();
        private volatile boolean rejectNext;
        private volatile CountDownLatch executionEntered = new CountDownLatch(0);
        private volatile CountDownLatch allowExecution = new CountDownLatch(0);

        @Override
        public void enqueue(ProcessingJob job) {
            if (rejectNext) {
                rejectNext = false;
                throw new IllegalStateException("test queue rejection");
            }
            jobs.add(job);
            accepted.incrementAndGet();
            CountDownLatch entered = executionEntered;
            CountDownLatch allowed = allowExecution;
            if (entered.getCount() > 0) {
                entered.countDown();
                try {
                    if (!allowed.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test queue gate timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test queue gate interrupted", interrupted);
                }
            }
        }

        int acceptedCount() {
            return accepted.get();
        }

        List<ProcessingJob> jobs() {
            return List.copyOf(jobs);
        }

        void rejectNext() {
            rejectNext = true;
        }

        void blockNextExecution() {
            executionEntered = new CountDownLatch(1);
            allowExecution = new CountDownLatch(1);
        }

        boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException {
            return executionEntered.await(timeout, unit);
        }

        void releaseBlockedExecution() {
            allowExecution.countDown();
        }

        void reset() {
            accepted.set(0);
            jobs.clear();
            rejectNext = false;
            executionEntered = new CountDownLatch(0);
            allowExecution = new CountDownLatch(0);
        }
    }
}
