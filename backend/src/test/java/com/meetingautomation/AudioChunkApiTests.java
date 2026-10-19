package com.meetingautomation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.UploadPolicy;
import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "DOCUMENT_PROVIDER=",
        "NOTIFICATION_PROVIDER=SLACK",
        "RENDER_AUDIO_GRANT_SECRET=test-audio-grant-secret",
        "MAX_CHUNK_BYTES=64",
        "ACCEPTED_AUDIO_MIME_TYPES=audio/webm,audio/mp4,audio/ogg"
})
@AutoConfigureMockMvc
@Import({ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class AudioChunkApiTests {
    private static final String SESSION_ID = "ms_audio-chunk-api-test";
    private static final Path AUDIO_ROOT = createTemporaryAudioRoot();
    private static final Path SETTINGS_ROOT = createTemporarySettingsRoot();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeetingSessionStore sessionStore;

    @Autowired
    private GlobalSettingsStore settingsStore;

    @Autowired
    private DocumentConnectionGate connectionGate;

    @DynamicPropertySource
    static void registerAudioDirectory(DynamicPropertyRegistry registry) {
        registry.add("TEMP_AUDIO_DIR", AUDIO_ROOT::toString);
        registry.add("DOCUMENT_SETTINGS_DIR", SETTINGS_ROOT::toString);
        registry.add("DOCUMENT_SETTINGS_ENCRYPTION_KEY",
                () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @BeforeEach
    void createUploadSessionAndCleanPreviousChunks() throws IOException {
        sessionStore.remove(SESSION_ID);
        settingsStore.transaction(current -> new GlobalSettingsStore.Change<>(
                new GlobalDocumentSettings(1, current.version() + 1,
                        new GlobalDocumentSettings.Connection("audio-chunk-test-connection", 1, "NOTION",
                                java.util.Map.of(), java.util.Map.of("token", "audio-chunk-test-token"),
                                "audio-chunk-test-root", "meetings-page", "participants-page", java.time.Instant.now()),
                        null, null, java.util.Map.of()), null));
        sessionStore.save(new MeetingSession(SESSION_ID, 1, "CREATED", 1,
                new MeetingSession.Meeting("Upload test", "default.md", List.of("participant-1"), "Asia/Seoul")),
                connectionGate.beginSession(1, SESSION_ID),
                new UploadPolicy(15, 32, List.of("audio/webm", "audio/mp4")));
        deleteRecursively(AUDIO_ROOT);
        Files.createDirectories(AUDIO_ROOT);
    }

    @AfterEach
    void removeUploadSession() {
        sessionStore.remove(SESSION_ID);
    }

    @AfterAll
    static void removeTemporaryAudioDirectory() throws IOException {
        deleteRecursively(AUDIO_ROOT);
        deleteRecursively(SETTINGS_ROOT);
    }

    @Test
    void validUploadGrantIsBoundToTheSessionSequenceAndExpectedAudioMetadata() throws Exception {
        byte[] audio = new byte[] {0, 1, 2};
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/meeting-sessions/" + SESSION_ID + "/audio/chunks/2/upload-grant")
                        .header("Idempotency-Key", "grant-2")
                        .contentType("application/json")
                        .content("{\"mimeType\":\"audio/webm\",\"bytes\":3,\"sha256\":\"" + sha256 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pathname").value("audio/" + SESSION_ID + "/chunks/2"))
                .andExpect(jsonPath("$.data.clientPayload").isString())
                .andExpect(jsonPath("$.data.expiresAt").isNumber());
    }

    @Test
    void validBinaryChunkIsStoredAndReturnsApi007Acknowledgement() throws Exception {
        byte[] audio = new byte[] {0, 1, 2, 13, 10, (byte) 0xff};

        mockMvc.perform(upload(SESSION_ID, "0", audio, "audio/webm", "audio-upload-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sequence").value(0))
                .andExpect(jsonPath("$.data.received").value(true));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).hasSize(1);
    }

    @Test
    void identicalBytesAtTheSameSequenceAreAcknowledgedWithoutCreatingAnotherChunk() throws Exception {
        byte[] audio = new byte[] {4, 5, 6, 7};

        mockMvc.perform(upload(SESSION_ID, "3", audio, "audio/webm", "audio-upload-first"))
                .andExpect(status().isOk());
        mockMvc.perform(upload(SESSION_ID, "3", audio, "audio/webm", "audio-upload-retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sequence").value(3))
                .andExpect(jsonPath("$.data.received").value(true));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).hasSize(1);
    }

    @Test
    void differentBytesAtTheSameSequenceReturnConflictAndRetainOriginalChunk() throws Exception {
        byte[] original = new byte[] {1, 2, 3};
        byte[] conflicting = new byte[] {1, 2, 4};

        mockMvc.perform(upload(SESSION_ID, "1", original, "audio/webm", "audio-upload-original"))
                .andExpect(status().isOk());
        mockMvc.perform(upload(SESSION_ID, "1", conflicting, "audio/webm", "audio-upload-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_CONFLICT"));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(original)).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(conflicting)).isEmpty();
    }

    @Test
    void missingSessionReturnsApi007NotFoundEnvelopeWithoutCreatingFiles() throws Exception {
        byte[] audio = new byte[] {8, 9};

        mockMvc.perform(upload("ms_missing-session", "0", audio, "audio/webm", "missing-session-upload"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).isEmpty();
    }

    @Test
    void uploadStatusForExistingSessionWithoutChunksReturnsEmptyAggregate() throws Exception {
        mockMvc.perform(get(statusPath(SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedSequences").isArray())
                .andExpect(jsonPath("$.data.receivedSequences").isEmpty())
                .andExpect(jsonPath("$.data.receivedChunks").value(0))
                .andExpect(jsonPath("$.data.totalBytes").value(0));
    }

    @Test
    void uploadStatusReportsUniqueNumericSequencesAndExactBytesWithoutChangingStorage() throws Exception {
        byte[] sequenceTen = new byte[] {10, 11, 12, 13, 14, 15, 16};
        byte[] sequenceTwo = new byte[] {2, 3, 4, 5, 6};
        byte[] sequenceZero = new byte[] {0, 1, 2};
        mockMvc.perform(upload(SESSION_ID, "10", sequenceTen, "audio/webm", "status-sequence-10"))
                .andExpect(status().isOk());
        mockMvc.perform(upload(SESSION_ID, "2", sequenceTwo, "audio/webm", "status-sequence-2"))
                .andExpect(status().isOk());
        mockMvc.perform(upload(SESSION_ID, "0", sequenceZero, "audio/webm", "status-sequence-0"))
                .andExpect(status().isOk());

        Path partial = sessionStorageDirectory().resolve(".upload-interrupted");
        Files.createDirectories(partial);
        Files.write(partial.resolve("chunk.bin"), new byte[] {99, 98, 97, 96});

        Map<String, String> storageBeforeQuery = storageSnapshot();
        mockMvc.perform(get(statusPath(SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedSequences").isArray())
                .andExpect(jsonPath("$.data.receivedSequences.length()").value(3))
                .andExpect(jsonPath("$.data.receivedSequences[0]").value(0))
                .andExpect(jsonPath("$.data.receivedSequences[1]").value(2))
                .andExpect(jsonPath("$.data.receivedSequences[2]").value(10))
                .andExpect(jsonPath("$.data.receivedChunks").value(3))
                .andExpect(jsonPath("$.data.totalBytes").value(15));
        mockMvc.perform(get(statusPath(SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedSequences").isArray())
                .andExpect(jsonPath("$.data.receivedSequences.length()").value(3))
                .andExpect(jsonPath("$.data.receivedSequences[0]").value(0))
                .andExpect(jsonPath("$.data.receivedSequences[1]").value(2))
                .andExpect(jsonPath("$.data.receivedSequences[2]").value(10))
                .andExpect(jsonPath("$.data.receivedChunks").value(3))
                .andExpect(jsonPath("$.data.totalBytes").value(15));

        org.assertj.core.api.Assertions.assertThat(storageSnapshot()).isEqualTo(storageBeforeQuery);
    }

    @Test
    void uploadStatusForMissingSessionReturnsSessionNotFound() throws Exception {
        mockMvc.perform(get(statusPath("ms_missing-upload-status")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void incompleteUploadPublishIsExcludedFromEmptyStatus() throws Exception {
        Path partial = sessionStorageDirectory().resolve(".upload-interrupted");
        Files.createDirectories(partial);
        Files.writeString(partial.resolve("chunk.bin"), "partial-private-audio");

        mockMvc.perform(get(statusPath(SESSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receivedSequences").isEmpty())
                .andExpect(jsonPath("$.data.receivedChunks").value(0))
                .andExpect(jsonPath("$.data.totalBytes").value(0));
    }

    @Test
    void uploadStatusStorageFailureUsesSafeInternalErrorWithoutLeakingPathOrAudio() throws Exception {
        byte[] audio = "status-private-audio-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(upload(SESSION_ID, "0", audio, "audio/webm", "status-storage-failure"))
                .andExpect(status().isOk());
        deleteRecursively(AUDIO_ROOT);
        Files.writeString(AUDIO_ROOT, "file prevents directory listing");

        mockMvc.perform(get(statusPath(SESSION_ID)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(result -> {
                    String response = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(response)
                            .doesNotContain(AUDIO_ROOT.toString())
                            .doesNotContain("status-private-audio-marker")
                            .doesNotContain(sha256(audio));
                });
    }

    @Test
    void invalidDigestAndOversizedBodyAreRejectedWithoutPublishingChunks() throws Exception {
        byte[] audio = new byte[] {8, 9};
        mockMvc.perform(put(path(SESSION_ID, "0"))
                        .contentType("audio/webm")
                        .header("X-Audio-SHA256", "0".repeat(64))
                        .header("X-Audio-Byte-Length", audio.length)
                        .header("Idempotency-Key", "invalid-digest-upload")
                        .content(audio))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));

        byte[] oversized = new byte[65];
        mockMvc.perform(upload(SESSION_ID, "1", oversized, "audio/webm", "oversized-upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(oversized)).isEmpty();
    }

    @Test
    void unsupportedMimeAndInvalidSequenceUseAudioChunkValidationEnvelope() throws Exception {
        byte[] audio = new byte[] {1, 2};

        mockMvc.perform(upload(SESSION_ID, "-1", audio, "audio/webm", "negative-sequence-upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));
        mockMvc.perform(upload(SESSION_ID, "2147483648", audio, "audio/webm", "overflow-sequence-upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));
        mockMvc.perform(upload(SESSION_ID, "0", audio, "audio/ogg", "unsupported-mime-upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).isEmpty();
    }

    @Test
    void storedSessionPolicyRejectsMimeAndSizeAllowedByCurrentApplicationDefaults() throws Exception {
        byte[] oversizedForSession = new byte[33];
        byte[] unsupportedBySession = new byte[] {21};

        mockMvc.perform(upload(SESSION_ID, "9", oversizedForSession, "audio/webm", "session-size-policy"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));
        mockMvc.perform(upload(SESSION_ID, "10", unsupportedBySession, "audio/ogg", "session-mime-policy"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(oversizedForSession)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(unsupportedBySession)).isEmpty();
    }

    @Test
    void signed32BitSequenceMaximumAndSessionChunkSizeMaximumAreAccepted() throws Exception {
        byte[] maximumChunk = new byte[32];
        java.util.Arrays.fill(maximumChunk, (byte) 42);

        mockMvc.perform(upload(SESSION_ID, "2147483647", maximumChunk, "audio/webm", "maximum-boundary-upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sequence").value(2147483647))
                .andExpect(jsonPath("$.data.received").value(true));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(maximumChunk)).hasSize(1);
    }

    @Test
    void declaredByteLengthMismatchIsRejectedWithoutPublishingChunk() throws Exception {
        byte[] audio = new byte[] {2, 3, 4};

        mockMvc.perform(put(path(SESSION_ID, "2"))
                        .contentType("audio/webm")
                        .header("X-Audio-SHA256", sha256(audio))
                        .header("X-Audio-Byte-Length", audio.length + 1)
                        .header("Idempotency-Key", "length-mismatch-upload")
                        .content(audio))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).isEmpty();
    }

    @Test
    void sameBytesWithDifferentMimeAtTheSameSequenceConflictAndKeepOriginalMimeChunk() throws Exception {
        byte[] audio = new byte[] {3, 4, 5};

        mockMvc.perform(upload(SESSION_ID, "4", audio, "audio/webm", "webm-upload"))
                .andExpect(status().isOk());
        mockMvc.perform(upload(SESSION_ID, "4", audio, "audio/mp4", "mp4-upload"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_CONFLICT"));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).hasSize(1);
    }

    @Test
    void concurrentIdenticalUploadsAtOneSequenceProduceOneStoredChunkAndTwoAcknowledgements() throws Exception {
        byte[] audio = new byte[] {11, 12, 13, 14};
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> performTogether(ready, start,
                    "concurrent-upload-first", audio));
            var second = executor.submit(() -> performTogether(ready, start,
                    "concurrent-upload-second", audio));
            org.assertj.core.api.Assertions.assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            org.assertj.core.api.Assertions.assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus())
                    .isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getStatus())
                    .isEqualTo(200);
        }

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).hasSize(1);
    }

    @Test
    void concurrentDifferentChunksAtOneSequenceProduceOneWinnerAndRetainItsBytes() throws Exception {
        byte[] webmChunk = new byte[] {31, 32, 33};
        byte[] mp4Chunk = new byte[] {31, 32, 34};
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var webm = executor.submit(() -> performTogether(ready, start,
                    "concurrent-webm-upload", webmChunk, "audio/webm"));
            var mp4 = executor.submit(() -> performTogether(ready, start,
                    "concurrent-mp4-upload", mp4Chunk, "audio/mp4"));
            org.assertj.core.api.Assertions.assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int webmStatus = webm.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            int mp4Status = mp4.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(List.of(webmStatus, mp4Status))
                    .containsExactlyInAnyOrder(200, 409);
            byte[] winningBytes = webmStatus == 200 ? webmChunk : mp4Chunk;
            byte[] losingBytes = webmStatus == 200 ? mp4Chunk : webmChunk;
            org.assertj.core.api.Assertions.assertThat(chunkContentFiles(winningBytes)).hasSize(1);
            org.assertj.core.api.Assertions.assertThat(chunkContentFiles(losingBytes)).isEmpty();
        }
    }

    @Test
    void missingRequiredHeaderAndEmptyBodyAreRejectedWithoutPublishingChunk() throws Exception {
        byte[] audio = new byte[] {7};
        mockMvc.perform(put(path(SESSION_ID, "5"))
                        .contentType("audio/webm")
                        .header("X-Audio-SHA256", sha256(audio))
                        .header("X-Audio-Byte-Length", audio.length)
                        .content(audio))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));

        mockMvc.perform(upload(SESSION_ID, "6", new byte[0], "audio/webm", "empty-audio-upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUDIO_CHUNK_INVALID"));

        org.assertj.core.api.Assertions.assertThat(chunkContentFiles(audio)).isEmpty();
    }

    @Test
    void storageFailureUsesSafeInternalErrorEnvelopeWithoutLeakingPathOrAudio() throws Exception {
        byte[] audio = "private-audio-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        deleteRecursively(AUDIO_ROOT);
        Files.writeString(AUDIO_ROOT, "file prevents directory creation");

        mockMvc.perform(upload(SESSION_ID, "7", audio, "audio/webm", "storage-failure-upload"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(result -> {
                    String response = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(response)
                            .doesNotContain(AUDIO_ROOT.toString())
                            .doesNotContain("private-audio-marker")
                            .doesNotContain(sha256(audio));
                });
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder upload(
            String sessionId, String sequence, byte[] audio, String mime, String idempotencyKey) throws Exception {
        return put(path(sessionId, sequence))
                .contentType(mime)
                .header("X-Audio-SHA256", sha256(audio))
                .header("X-Audio-Byte-Length", audio.length)
                .header("Idempotency-Key", idempotencyKey)
                .content(audio);
    }

    private static String path(String sessionId, String sequence) {
        return "/api/v1/meeting-sessions/" + sessionId + "/audio/chunks/" + sequence;
    }

    private static String statusPath(String sessionId) {
        return "/api/v1/meeting-sessions/" + sessionId + "/audio";
    }

    private static Path sessionStorageDirectory() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(SESSION_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return AUDIO_ROOT.resolve(hash);
    }

    private Map<String, String> storageSnapshot() throws IOException {
        try (var paths = Files.walk(AUDIO_ROOT)) {
            Map<String, String> snapshot = new TreeMap<>();
            for (Path path : paths.toList()) {
                String relative = AUDIO_ROOT.relativize(path).toString();
                snapshot.put(relative, Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis());
            }
            return snapshot;
        }
    }

    private List<Path> chunkContentFiles(byte[] expectedBytes) throws IOException {
        try (var paths = Files.walk(AUDIO_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> contentEquals(path, expectedBytes))
                    .toList();
        }
    }

    private org.springframework.test.web.servlet.MvcResult performTogether(
            CountDownLatch ready, CountDownLatch start, String idempotencyKey, byte[] audio) throws Exception {
        return performTogether(ready, start, idempotencyKey, audio, "audio/webm");
    }

    private org.springframework.test.web.servlet.MvcResult performTogether(
            CountDownLatch ready, CountDownLatch start, String idempotencyKey, byte[] audio, String mime) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent upload did not start.");
        return mockMvc.perform(upload(SESSION_ID, "8", audio, mime, idempotencyKey)).andReturn();
    }

    private static boolean contentEquals(Path path, byte[] expectedBytes) {
        try {
            return java.util.Arrays.equals(Files.readAllBytes(path), expectedBytes);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String sha256(byte[] audio) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio));
    }

    private static Path createTemporaryAudioRoot() {
        try {
            return Files.createTempDirectory("meeting-audio-api-test-");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Path createTemporarySettingsRoot() {
        try {
            return Files.createTempDirectory("meeting-audio-settings-test-");
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
}
