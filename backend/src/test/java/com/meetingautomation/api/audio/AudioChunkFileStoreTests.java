package com.meetingautomation.api.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioChunkFileStoreTests {
    @TempDir
    Path audioRoot;

    @Test
    void oversizedStreamStopsAfterOneByteBeyondPolicyAndPublishesNoChunk() throws Exception {
        AudioChunkFileStore store = new AudioChunkFileStore(audioRoot.toString());
        CountingInputStream requestBody = new CountingInputStream(new byte[8192]);

        assertThatThrownBy(() -> store.store("ms_streaming-limit", 0, "audio/webm", 32,
                sha256(new byte[32]), 32, requestBody))
                .isInstanceOf(AudioChunkInvalidException.class);

        assertThat(requestBody.bytesRead()).isEqualTo(33);
        try (var paths = Files.walk(audioRoot)) {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void storageRootBelowRegularFileFailsInsteadOfReturningEmptyStatus() throws Exception {
        Path fileComponent = audioRoot.resolve("not-a-directory");
        Files.writeString(fileComponent, "this path component is a regular file");
        AudioChunkFileStore store = new AudioChunkFileStore(fileComponent.resolve("audio"));

        assertThatThrownBy(() -> store.completedChunks("ms_status-storage-error"))
                .isInstanceOf(AudioStorageException.class);
    }

    @Test
    void missingRootOrSessionDirectoryReturnsEmptyStatus() throws Exception {
        AudioChunkFileStore store = new AudioChunkFileStore(audioRoot);
        assertThat(store.completedChunks("ms_missing-status")).isEmpty();

        Files.delete(audioRoot);
        assertThat(store.completedChunks("ms_missing-status")).isEmpty();
    }

    @Test
    void openChunkRejectsMissingAndRegularFileBlockedRoots() throws Exception {
        AudioChunkFileStore missingRootStore = new AudioChunkFileStore(audioRoot.resolve("missing"));
        assertThatThrownBy(() -> missingRootStore.openChunk("ms_missing-open", 0))
                .isInstanceOf(AudioStorageException.class);

        Path blocker = audioRoot.resolve("regular-file");
        Files.writeString(blocker, "not a directory");
        AudioChunkFileStore blockedRootStore = new AudioChunkFileStore(blocker.resolve("audio"));
        assertThatThrownBy(() -> blockedRootStore.openChunk("ms_blocked-open", 0))
                .isInstanceOf(AudioStorageException.class);
    }

    @Test
    void openChunkRejectsSymbolicLinkAsStorageRoot() throws Exception {
        Path target = audioRoot.resolve("real-root");
        Path link = audioRoot.resolve("root-link");
        Files.createDirectories(target);
        byte[] audio = new byte[] {7, 11, 13};
        new AudioChunkFileStore(target).store("ms_linked-open", 0, "audio/webm", audio.length,
                sha256(audio), 64, new ByteArrayInputStream(audio));
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.abort("The current Windows account does not allow creating symbolic links.");
        }
        AudioChunkFileStore linkedRootStore = new AudioChunkFileStore(link);
        assertThatThrownBy(() -> linkedRootStore.openChunk("ms_linked-open", 0))
                .isInstanceOf(AudioStorageException.class);
    }

    @Test
    void sessionIdIsHashedBeforeBuildingChunkPaths() throws Exception {
        AudioChunkFileStore store = new AudioChunkFileStore(audioRoot.toString());
        byte[] audio = new byte[] {3, 8, 13};

        store.store("../../outside", 1, "audio/webm", audio.length,
                sha256(audio), 32, new ByteArrayInputStream(audio));

        List<Path> files;
        try (var paths = Files.walk(audioRoot)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        assertThat(files).isNotEmpty();
        assertThat(files.stream().anyMatch(path -> hasContent(path, audio))).isTrue();
        assertThat(audioRoot.resolve("../../outside").normalize()).doesNotExist();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static boolean hasContent(Path path, byte[] expected) {
        try {
            return java.util.Arrays.equals(Files.readAllBytes(path), expected);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        private int bytesRead;

        private CountingInputStream(byte[] source) {
            super(source);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int count = super.read(buffer, offset, length);
            if (count > 0) bytesRead += count;
            return count;
        }

        int bytesRead() {
            return bytesRead;
        }
    }
}
