package com.meetingautomation.api.audio;

import java.io.InputStream;
import java.nio.file.Path;

/** Test helper that keeps the chunk store's package-level write boundary intact. */
public final class AudioChunkTestFixtures {
    private AudioChunkTestFixtures() {
    }

    public static AudioChunkFileStore newStore(Path directory) {
        return new AudioChunkFileStore(directory);
    }

    public static AudioAssemblyFileStore newAssemblyStore(Path directory, AudioChunkFileStore chunkStore) {
        return new AudioAssemblyFileStore(directory, chunkStore);
    }

    public static void store(AudioChunkFileStore store, String sessionId, int sequence, String mimeType,
            long declaredLength, String expectedSha256, int maxChunkBytes, InputStream body) {
        store.store(sessionId, sequence, mimeType, declaredLength, expectedSha256, maxChunkBytes, body);
    }
}
