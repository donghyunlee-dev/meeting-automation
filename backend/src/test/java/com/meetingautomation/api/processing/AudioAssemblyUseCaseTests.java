package com.meetingautomation.api.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.meetingautomation.api.audio.AudioAssemblyFileStore;
import com.meetingautomation.api.audio.AudioAssemblyStorage;
import com.meetingautomation.api.audio.AudioAssemblyStorageException;
import com.meetingautomation.api.audio.AudioChunkFileStore;
import com.meetingautomation.api.audio.AudioChunkTestFixtures;
import com.meetingautomation.api.session.UploadPolicy;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioAssemblyUseCaseTests {
    private static final String SESSION_ID = "ms_audio-assembly-use-case";
    private static final String TRACE_ID = "tr_audio-assembly-test";
    private static final UploadPolicy POLICY = new UploadPolicy(15, 64, List.of("audio/webm", "audio/mp4"));

    @TempDir
    Path audioRoot;

    private AudioChunkFileStore chunkStore;
    private AudioAssemblyStorage storage;
    private AudioAssemblyUseCase useCase;

    @BeforeEach
    void setUp() {
        chunkStore = AudioChunkTestFixtures.newStore(audioRoot);
        storage = AudioChunkTestFixtures.newAssemblyStore(audioRoot, chunkStore);
        useCase = new AudioAssemblyUseCase(storage, 60);
    }

    @Test
    void assemblesChunksInSequenceAndRemovesSourcesAfterAtomicPublish() throws Exception {
        storeChunk(1, new byte[] {4, 5}, "audio/webm");
        storeChunk(0, new byte[] {1, 2, 3}, "audio/webm");

        AudioAssemblyStorage.Assembly result = useCase.assemble(job(2, 30_000, "audio/webm"), POLICY, TRACE_ID);

        assertThat(Files.readAllBytes(result.reference())).containsExactly(1, 2, 3, 4, 5);
        assertThat(result.mimeType()).isEqualTo("audio/webm");
        assertThat(result.bytes()).isEqualTo(5);
        assertThat(result.sha256()).isEqualTo(sha256(new byte[] {1, 2, 3, 4, 5}));
        assertThat(result.reference().toString()).doesNotContain(SESSION_ID);
        assertThat(chunkStore.completedChunks(SESSION_ID)).isEmpty();
        assertThat(Files.exists(result.reference())).isTrue();
    }

    @Test
    void rejectsMissingOrExtraSequenceWithoutPublishingAndPreservesSourceChunks() throws Exception {
        storeChunk(0, new byte[] {1}, "audio/webm");
        storeChunk(2, new byte[] {3}, "audio/webm");

        assertThatThrownBy(() -> useCase.assemble(job(2, 30_000, "audio/webm"), POLICY, TRACE_ID))
                .isInstanceOf(AudioAssemblyException.class);

        assertThat(chunkStore.completedChunks(SESSION_ID)).extracting(AudioChunkFileStore.CompletedChunk::sequence)
                .containsExactly(0, 2);
        assertThat(storage.findAssembly(SESSION_ID)).isEmpty();
        assertNoPartialFiles();
    }

    @Test
    void rejectsMixedMimeAndPreservesAllChunks() throws Exception {
        storeChunk(0, new byte[] {1}, "audio/webm");
        storeChunk(1, new byte[] {2}, "audio/mp4");

        assertThatThrownBy(() -> useCase.assemble(job(2, 30_000, "audio/webm"), POLICY, TRACE_ID))
                .isInstanceOf(AudioAssemblyException.class);

        assertThat(chunkStore.completedChunks(SESSION_ID)).hasSize(2);
        assertThat(storage.findAssembly(SESSION_ID)).isEmpty();
        assertNoPartialFiles();
    }

    @Test
    void rejectsChunkAboveSessionPolicyLimitBeforeOpeningAssemblyOutput() throws Exception {
        storeChunk(0, new byte[] {1, 2, 3}, "audio/webm");
        UploadPolicy stricterPolicy = new UploadPolicy(15, 2, List.of("audio/webm"));

        assertThatThrownBy(() -> useCase.assemble(job(1, 15_000, "audio/webm"), stricterPolicy, TRACE_ID))
                .isInstanceOf(AudioAssemblyException.class);

        assertThat(chunkStore.completedChunks(SESSION_ID)).hasSize(1);
        assertThat(storage.findAssembly(SESSION_ID)).isEmpty();
        assertNoPartialFiles();
    }

    @Test
    void detectsStoredChunkChecksumMismatchAndRemovesPartialOutputOnly() throws Exception {
        storeChunk(0, new byte[] {1, 2, 3}, "audio/webm");
        Path source = chunkAudioPath(0);
        Files.write(source, new byte[] {9, 9, 9});

        assertThatThrownBy(() -> useCase.assemble(job(1, 15_000, "audio/webm"), POLICY, TRACE_ID))
                .isInstanceOf(AudioAssemblyException.class);

        assertThat(Files.readAllBytes(source)).containsExactly(9, 9, 9);
        assertThat(storage.findAssembly(SESSION_ID)).isEmpty();
        assertNoPartialFiles();
    }

    @Test
    void reusesValidatedAssemblyOnReplayAfterSourceChunksWereRemoved() throws Exception {
        storeChunk(0, new byte[] {6, 7, 8}, "audio/webm");
        ProcessingJob job = job(1, 15_000, "audio/webm");

        AudioAssemblyStorage.Assembly first = useCase.assemble(job, POLICY, TRACE_ID);
        AudioAssemblyStorage.Assembly replay = useCase.assemble(job, POLICY, TRACE_ID);

        assertThat(replay).isEqualTo(first);
        assertThat(Files.readAllBytes(replay.reference())).containsExactly(6, 7, 8);
        assertThat(chunkStore.completedChunks(SESSION_ID)).isEmpty();
        assertThat(storage.findAssembly(SESSION_ID)).contains(first);
    }

    @Test
    void rejectsReplayWhenPreviouslyPublishedOutputWasChanged() throws Exception {
        storeChunk(0, new byte[] {6, 7, 8}, "audio/webm");
        ProcessingJob job = job(1, 15_000, "audio/webm");
        AudioAssemblyStorage.Assembly first = useCase.assemble(job, POLICY, TRACE_ID);
        Files.write(first.reference(), new byte[] {0, 0, 0});

        assertThatThrownBy(() -> useCase.assemble(job, POLICY, TRACE_ID))
                .isInstanceOf(AudioAssemblyException.class);

        assertThat(Files.readAllBytes(first.reference())).containsExactly(0, 0, 0);
        assertThat(chunkStore.completedChunks(SESSION_ID)).isEmpty();
    }

    @Test
    void retainsPublishedOutputAndRetriesSourceCleanupWithoutReassembling() throws Exception {
        storeChunk(0, new byte[] {2, 4, 6}, "audio/webm");
        AudioAssemblyStorage cleanupFailsOnce = new DelegatingStorage(storage) {
            private boolean failed = false;

            @Override
            public void deleteSourceChunks(String sessionId) {
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("simulated source cleanup failure");
                }
                super.deleteSourceChunks(sessionId);
            }
        };

        AudioAssemblyStorage.Assembly first = new AudioAssemblyUseCase(cleanupFailsOnce, 60)
                .assemble(job(1, 15_000, "audio/webm"), POLICY, TRACE_ID);

        assertThat(Files.readAllBytes(first.reference())).containsExactly(2, 4, 6);
        assertThat(chunkStore.completedChunks(SESSION_ID)).hasSize(1);
        AudioAssemblyStorage.Assembly replay = useCase.assemble(job(1, 15_000, "audio/webm"), POLICY, TRACE_ID);
        assertThat(replay).isEqualTo(first);
        assertThat(chunkStore.completedChunks(SESSION_ID)).isEmpty();
        assertThat(storage.findAssembly(SESSION_ID)).contains(first);
    }

    @Test
    void cleansStagedPartAndPreservesSourcesWhenAtomicPublishFails() throws Exception {
        storeChunk(0, new byte[] {3, 5, 7}, "audio/webm");
        AudioAssemblyStorage publishFails = new DelegatingStorage(storage) {
            @Override
            public Assembly publish(String sessionId, String mimeType, long bytes, String sha256) {
                try (var paths = Files.walk(audioRoot)) {
                    assertThat(paths.anyMatch(path -> path.getFileName().toString().endsWith(".part"))).isTrue();
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                }
                throw new AudioAssemblyStorageException();
            }
        };

        assertThatThrownBy(() -> new AudioAssemblyUseCase(publishFails, 60)
                .assemble(job(1, 15_000, "audio/webm"), POLICY, TRACE_ID))
                .isInstanceOf(AudioAssemblyException.class);

        assertThat(storage.findAssembly(SESSION_ID)).isEmpty();
        assertNoPartialFiles();
        assertThat(chunkStore.completedChunks(SESSION_ID)).hasSize(1);
    }

    @Test
    void rejectsInvalidJobMimeAndDurationBeforeReadingOrChangingChunks() throws Exception {
        storeChunk(0, new byte[] {1}, "audio/webm");

        assertThatThrownBy(() -> useCase.assemble(job(1, 15_000, "audio/ogg"), POLICY, TRACE_ID))
                .isInstanceOf(AudioAssemblyException.class);
        assertThatThrownBy(() -> useCase.assemble(job(1, 3_600_001, "audio/webm"), POLICY, TRACE_ID))
                .isInstanceOf(AudioAssemblyException.class);

        assertThat(chunkStore.completedChunks(SESSION_ID)).hasSize(1);
        assertThat(storage.findAssembly(SESSION_ID)).isEmpty();
    }

    @Test
    void logsCorrelationAndOutcomeFieldsWithoutAudioOrFilesystemPath() throws Exception {
        byte[] audio = "private-audio-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storeChunk(0, audio, "audio/webm");
        Logger logger = (Logger) LoggerFactory.getLogger(AudioAssemblyUseCase.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        AudioAssemblyStorage.Assembly result;
        try {
            result = useCase.assemble(job(1, 15_000, "audio/webm"), POLICY, TRACE_ID);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        ILoggingEvent event = appender.list.stream()
                .filter(candidate -> candidate.getMessage().equals("Audio assembly succeeded."))
                .findFirst().orElseThrow();
        assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key)
                .contains("sessionId", "traceId", "stage", "outcome", "reusedOutput", "assembledBytes");
        assertThat(event.getKeyValuePairs().toString()).contains(SESSION_ID, TRACE_ID, "AUDIO_ASSEMBLY", "succeeded");
        assertThat(event.getKeyValuePairs().toString()).doesNotContain(result.reference().toString(),
                "private-audio-payload");
    }

    private void storeChunk(int sequence, byte[] bytes, String mimeType) throws Exception {
        AudioChunkTestFixtures.store(chunkStore, SESSION_ID, sequence, mimeType, bytes.length, sha256(bytes),
                POLICY.maxChunkBytes(), new ByteArrayInputStream(bytes));
    }

    private Path chunkAudioPath(int sequence) throws Exception {
        try (var paths = Files.walk(audioRoot)) {
            return paths.filter(path -> path.getFileName().toString().equals("chunk.bin"))
                    .filter(path -> path.getParent().getFileName().toString().equals(Integer.toString(sequence)))
                    .findFirst().orElseThrow();
        }
    }

    private void assertNoPartialFiles() throws Exception {
        try (var paths = Files.walk(audioRoot)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".part"))).isEmpty();
        }
    }

    private static ProcessingJob job(int chunks, long durationMs, String mimeType) {
        return new ProcessingJob(SESSION_ID, chunks, durationMs, mimeType);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static class DelegatingStorage implements AudioAssemblyStorage {
        private final AudioAssemblyStorage delegate;

        private DelegatingStorage(AudioAssemblyStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Chunk> chunks(String sessionId) {
            return delegate.chunks(sessionId);
        }

        @Override
        public java.io.InputStream openChunk(String sessionId, int sequence) {
            return delegate.openChunk(sessionId, sequence);
        }

        @Override
        public OutputStream createPart(String sessionId) {
            return delegate.createPart(sessionId);
        }

        @Override
        public Assembly publish(String sessionId, String mimeType, long bytes, String sha256) {
            return delegate.publish(sessionId, mimeType, bytes, sha256);
        }

        @Override
        public java.util.Optional<Assembly> findAssembly(String sessionId) {
            return delegate.findAssembly(sessionId);
        }

        @Override
        public void discardPart(String sessionId) {
            delegate.discardPart(sessionId);
        }

        @Override
        public void deleteSourceChunks(String sessionId) {
            delegate.deleteSourceChunks(sessionId);
        }
    }
}
