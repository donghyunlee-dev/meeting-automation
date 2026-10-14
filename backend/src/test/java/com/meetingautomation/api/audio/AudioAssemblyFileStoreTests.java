package com.meetingautomation.api.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioAssemblyFileStoreTests {
    @TempDir
    Path audioRoot;

    @Test
    void publishesCompleteAssemblyDirectoryAtomicallyAndValidatesItOnRead() throws Exception {
        AudioChunkFileStore chunkStore = new AudioChunkFileStore(audioRoot);
        AudioAssemblyFileStore store = new AudioAssemblyFileStore(audioRoot, chunkStore);
        byte[] audio = new byte[] {3, 1, 4, 1, 5};
        String digest = sha256(audio);
        chunkStore.store("../../session-traversal", 0, "audio/webm", audio.length, digest, 64,
                new ByteArrayInputStream(audio));
        try (var input = chunkStore.openChunk("../../session-traversal", 0);
                var output = store.createPart("../../session-traversal")) {
            input.transferTo(output);
        }

        AudioAssemblyStorage.Assembly published = store.publish("../../session-traversal", "audio/webm",
                audio.length, digest);

        assertThat(Files.readAllBytes(Path.of(published.reference()))).containsExactly(audio);
        assertThat(published.reference().toString()).doesNotContain("session-traversal");
        assertThat(store.findAssembly("../../session-traversal")).contains(published);
        assertThat(partialFiles()).isEmpty();
    }

    @Test
    void rejectsInvalidStoredAssemblyMetadataInsteadOfReturningUnverifiedReference() throws Exception {
        AudioAssemblyFileStore store = new AudioAssemblyFileStore(audioRoot, new AudioChunkFileStore(audioRoot));
        AudioAssemblyStorage.Assembly published = publish(store, "ms_corrupt-assembly", new byte[] {1, 2});
        Files.writeString(Path.of(published.reference()).getParent().resolve("metadata"), "audio/webm\n1\ninvalid\n");

        assertThatThrownBy(() -> store.findAssembly("ms_corrupt-assembly"))
                .isInstanceOf(AudioAssemblyStorageException.class);
    }

    @Test
    void rejectsModifiedStoredAssemblyBytesByVerifyingLengthAndDigest() throws Exception {
        AudioAssemblyFileStore store = new AudioAssemblyFileStore(audioRoot, new AudioChunkFileStore(audioRoot));
        AudioAssemblyStorage.Assembly published = publish(store, "ms_corrupt-bytes", new byte[] {1, 2});
        Files.write(Path.of(published.reference()), new byte[] {1, 2, 3});

        assertThatThrownBy(() -> store.findAssembly("ms_corrupt-bytes"))
                .isInstanceOf(AudioAssemblyStorageException.class);
    }

    @Test
    void discardingFailedPartRemovesOnlyThePartialAssembly() throws Exception {
        AudioChunkFileStore chunkStore = new AudioChunkFileStore(audioRoot);
        AudioAssemblyFileStore store = new AudioAssemblyFileStore(audioRoot, chunkStore);
        byte[] chunk = new byte[] {8, 9};
        chunkStore.store("ms_partial", 0, "audio/webm", chunk.length, sha256(chunk), 64,
                new ByteArrayInputStream(chunk));
        try (var output = store.createPart("ms_partial")) {
            output.write(new byte[] {7});
        }
        store.discardPart("ms_partial");

        assertThat(partialFiles()).isEmpty();
        assertThat(chunkStore.completedChunks("ms_partial")).hasSize(1);
        assertThat(store.findAssembly("ms_partial")).isEmpty();
    }

    @Test
    void createPartFailureThroughSymlinkedRootPreservesTargetStagingData() throws Exception {
        String sessionId = "ms_assembly-root-link";
        Path target = audioRoot.resolve("real-assembly-root");
        Path link = audioRoot.resolve("assembly-root-link");
        String sessionHash = sessionHash(sessionId);
        Path stagingDirectory = target.resolve(sessionHash).resolve(".assembly-" + sessionHash + ".tmp");
        Path sentinel = stagingDirectory.resolve("sentinel.txt");
        Files.createDirectories(stagingDirectory);
        Files.writeString(sentinel, "preserve target files");
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.abort("The current Windows account does not allow creating symbolic links.");
        }
        AudioAssemblyFileStore store = new AudioAssemblyFileStore(link, new AudioChunkFileStore(link));

        assertThatThrownBy(() -> store.createPart(sessionId)).isInstanceOf(AudioAssemblyStorageException.class);

        assertThat(Files.readString(sentinel)).isEqualTo("preserve target files");
    }

    @Test
    void removesOnlyCanonicalChunkDirectoriesAndKeepsPublishedAssembly() throws Exception {
        AudioChunkFileStore chunkStore = new AudioChunkFileStore(audioRoot);
        AudioAssemblyFileStore store = new AudioAssemblyFileStore(audioRoot, chunkStore);
        byte[] chunk = new byte[] {8, 9};
        chunkStore.store("ms_cleanup", 0, "audio/webm", chunk.length, sha256(chunk), 64,
                new ByteArrayInputStream(chunk));
        AudioAssemblyStorage.Assembly published = publish(store, "ms_cleanup", new byte[] {8, 9});

        chunkStore.deleteAssemblySources("ms_cleanup");

        assertThat(chunkStore.completedChunks("ms_cleanup")).isEmpty();
        assertThat(store.findAssembly("ms_cleanup")).contains(published);
    }

    private static AudioAssemblyStorage.Assembly publish(AudioAssemblyFileStore store, String sessionId,
            byte[] audio) throws Exception {
        try (var output = store.createPart(sessionId)) {
            output.write(audio);
        }
        return store.publish(sessionId, "audio/webm", audio.length, sha256(audio));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String sessionHash(String sessionId) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(sessionId.getBytes(StandardCharsets.UTF_8)));
    }

    private java.util.List<Path> partialFiles() throws Exception {
        try (var paths = Files.walk(audioRoot)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".part")).toList();
        }
    }
}
