package com.meetingautomation.api.audio;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Provider-neutral boundary for private, durable Audio objects. Handles are opaque and never API values. */
public interface PrivateAudioStoragePort {
    StoredObject store(String sessionId, ObjectType type, int sequence, String mimeType, Instant expiresAt,
            InputStream body, long bytes, String sha256);

    InputStream open(String objectId);

    Optional<StoredObject> find(String objectId);

    List<StoredObject> listForSession(String sessionId);

    List<StoredObject> listExpired(Instant now);

    void extendExpiry(String objectId, Instant expiresAt);

    void delete(String objectId);

    enum ObjectType { CHUNK, ASSEMBLED }

    record StoredObject(String objectId, String sessionId, ObjectType type, int sequence, String mimeType,
            long bytes, String sha256, Instant expiresAt) { }
}
