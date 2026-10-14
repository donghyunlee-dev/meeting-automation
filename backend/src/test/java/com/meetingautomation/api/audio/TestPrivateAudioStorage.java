package com.meetingautomation.api.audio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small synthetic storage fixture used by Spring API tests; it has no filesystem or cloud access. */
public final class TestPrivateAudioStorage implements PrivateAudioStoragePort {
    private final ConcurrentMap<String, Entry> objects = new ConcurrentHashMap<>();
    private volatile boolean failNextStore;
    private volatile boolean failNextList;
    private volatile boolean failNextDelete;

    public void failNextStore() { failNextStore = true; }
    public void failNextList() { failNextList = true; }
    public void failNextDelete() { failNextDelete = true; }
    public void clearSession(String sessionId) {
        objects.entrySet().removeIf(entry -> entry.getValue().metadata().sessionId().equals(sessionId));
    }

    @Override
    public StoredObject store(String sessionId, ObjectType type, int sequence, String mimeType, Instant expiresAt,
            InputStream body, long bytes, String sha256) {
        if (failNextStore) {
            failNextStore = false;
            throw new AudioStorageException();
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            body.transferTo(output);
            byte[] contents = output.toByteArray();
            if (contents.length != bytes || !digest(contents).equals(sha256)) throw new AudioChunkInvalidException();
            String id = UUID.randomUUID().toString();
            StoredObject metadata = new StoredObject(id, sessionId, type, sequence, mimeType, bytes, sha256, expiresAt);
            objects.put(id, new Entry(metadata, contents));
            return metadata;
        } catch (IOException failure) {
            throw new AudioStorageException();
        }
    }

    @Override public InputStream open(String objectId) {
        Entry entry = objects.get(objectId);
        if (entry == null) throw new AudioStorageException();
        return new ByteArrayInputStream(entry.contents());
    }
    @Override public Optional<StoredObject> find(String objectId) {
        Entry entry = objects.get(objectId);
        return entry == null ? Optional.empty() : Optional.of(entry.metadata());
    }
    @Override public List<StoredObject> listForSession(String sessionId) {
        if (failNextList) {
            failNextList = false;
            throw new AudioStorageException();
        }
        return objects.values().stream().map(Entry::metadata)
                .filter(value -> value.sessionId().equals(sessionId)).toList();
    }
    @Override public List<StoredObject> listExpired(Instant now) {
        return objects.values().stream().map(Entry::metadata)
                .filter(value -> value.expiresAt() != null && !value.expiresAt().isAfter(now)).toList();
    }
    @Override public void extendExpiry(String objectId, Instant expiresAt) {
        objects.computeIfPresent(objectId, (id, entry) -> {
            var prior = entry.metadata();
            var updated = new StoredObject(id, prior.sessionId(), prior.type(), prior.sequence(), prior.mimeType(),
                    prior.bytes(), prior.sha256(), expiresAt);
            return new Entry(updated, entry.contents());
        });
    }
    @Override public void delete(String objectId) {
        if (failNextDelete) { failNextDelete = false; throw new AudioStorageException(); }
        objects.remove(objectId);
    }

    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Entry(StoredObject metadata, byte[] contents) { }
}
