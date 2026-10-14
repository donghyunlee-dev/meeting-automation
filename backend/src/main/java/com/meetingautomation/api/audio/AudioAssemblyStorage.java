package com.meetingautomation.api.audio;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

/** Storage boundary used by audio assembly without exposing filesystem details to the use case. */
public interface AudioAssemblyStorage {
    List<Chunk> chunks(String sessionId);

    InputStream openChunk(String sessionId, int sequence);

    OutputStream createPart(String sessionId);

    Assembly publish(String sessionId, String mimeType, long bytes, String sha256);

    Optional<Assembly> findAssembly(String sessionId);

    InputStream openAssembly(Assembly assembly);

    void discardPart(String sessionId);

    void deleteSourceChunks(String sessionId);

    void deleteAssembly(String sessionId);

    default void setSessionExpiry(String sessionId, java.time.Instant expiresAt) { }

    default void deleteAllAudio(String sessionId) {
        deleteSourceChunks(sessionId);
        deleteAssembly(sessionId);
    }

    record Chunk(int sequence, String mimeType, long bytes, String sha256) {
    }

    /** Opaque storage handle for later processing stages; never a user-facing value. */
    record Assembly(String reference, String mimeType, long bytes, String sha256) {
    }
}
