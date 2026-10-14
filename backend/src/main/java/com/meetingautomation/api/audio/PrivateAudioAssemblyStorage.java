package com.meetingautomation.api.audio;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Private object-store implementation of chunk assembly. Audio bytes are never buffered in process memory. */
public final class PrivateAudioAssemblyStorage implements AudioAssemblyStorage {
    private final PrivateAudioStoragePort storage;
    private final Clock clock;
    private final ConcurrentMap<String, PartDigest> parts = new ConcurrentHashMap<>();

    public PrivateAudioAssemblyStorage(PrivateAudioStoragePort storage) {
        this(storage, Clock.systemUTC());
    }

    PrivateAudioAssemblyStorage(PrivateAudioStoragePort storage, Clock clock) {
        if (storage == null) throw new IllegalArgumentException("Private audio storage is required.");
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    public List<Chunk> chunks(String sessionId) {
        return storage.listForSession(sessionId).stream()
                .filter(object -> object.type() == PrivateAudioStoragePort.ObjectType.CHUNK)
                .map(object -> new Chunk(object.sequence(), object.mimeType(), object.bytes(), object.sha256()))
                .sorted(Comparator.comparingInt(Chunk::sequence)).toList();
    }

    @Override
    public InputStream openChunk(String sessionId, int sequence) {
        return storage.listForSession(sessionId).stream()
                .filter(object -> object.type() == PrivateAudioStoragePort.ObjectType.CHUNK
                        && object.sequence() == sequence)
                .findFirst().map(object -> storage.open(object.objectId()))
                .orElseThrow(AudioAssemblyStorageException::new);
    }

    @Override
    public OutputStream createPart(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new AudioAssemblyStorageException();
        PartDigest part = new PartDigest();
        if (parts.putIfAbsent(sessionId, part) != null) throw new AudioAssemblyStorageException();
        return part;
    }

    @Override
    public Assembly publish(String sessionId, String mimeType, long bytes, String sha256) {
        PartDigest part = parts.get(sessionId);
        if (part == null || mimeType == null || mimeType.isBlank() || bytes < 1
                || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new AudioAssemblyStorageException();
        }
        if (part.bytes() != bytes || !part.sha256().equals(sha256)) {
            throw new AudioAssemblyStorageException();
        }
        try {
            List<PrivateAudioStoragePort.StoredObject> chunks = storage.listForSession(sessionId).stream()
                    .filter(object -> object.type() == PrivateAudioStoragePort.ObjectType.CHUNK)
                    .sorted(Comparator.comparingInt(PrivateAudioStoragePort.StoredObject::sequence)).toList();
            Iterator<PrivateAudioStoragePort.StoredObject> iterator = chunks.iterator();
            Enumeration<InputStream> inputs = new Enumeration<>() {
                @Override public boolean hasMoreElements() { return iterator.hasNext(); }
                @Override public InputStream nextElement() { return storage.open(iterator.next().objectId()); }
            };
            PrivateAudioStoragePort.StoredObject stored;
            try (SequenceInputStream content = new SequenceInputStream(inputs)) {
                stored = storage.store(sessionId, PrivateAudioStoragePort.ObjectType.ASSEMBLED, -1, mimeType,
                        clock.instant().plus(PrivateAudioStoragePort.TEMPORARY_LEASE),
                        content, bytes, sha256);
            }
            parts.remove(sessionId, part);
            return assembly(stored);
        } catch (IOException | RuntimeException failure) {
            throw new AudioAssemblyStorageException();
        }
    }

    @Override
    public Optional<Assembly> findAssembly(String sessionId) {
        return storage.listForSession(sessionId).stream()
                .filter(object -> object.type() == PrivateAudioStoragePort.ObjectType.ASSEMBLED)
                .findFirst().map(PrivateAudioAssemblyStorage::assembly);
    }

    @Override
    public InputStream openAssembly(Assembly assembly) {
        if (assembly == null || assembly.reference() == null || assembly.reference().isBlank()) {
            throw new AudioAssemblyStorageException();
        }
        return storage.open(assembly.reference());
    }

    @Override
    public void discardPart(String sessionId) {
        parts.remove(sessionId);
    }

    @Override
    public void deleteSourceChunks(String sessionId) {
        storage.listForSession(sessionId).stream()
                .filter(object -> object.type() == PrivateAudioStoragePort.ObjectType.CHUNK)
                .forEach(object -> storage.delete(object.objectId()));
    }

    @Override
    public void deleteAssembly(String sessionId) {
        storage.listForSession(sessionId).stream()
                .filter(object -> object.type() == PrivateAudioStoragePort.ObjectType.ASSEMBLED)
                .forEach(object -> storage.delete(object.objectId()));
    }

    @Override
    public void setSessionExpiry(String sessionId, java.time.Instant expiresAt) {
        storage.listForSession(sessionId).forEach(object -> storage.extendExpiry(object.objectId(), expiresAt));
    }

    @Override
    public void deleteAllAudio(String sessionId) {
        storage.listForSession(sessionId).forEach(object -> storage.delete(object.objectId()));
    }

    private static Assembly assembly(PrivateAudioStoragePort.StoredObject object) {
        return new Assembly(object.objectId(), object.mimeType(), object.bytes(), object.sha256());
    }

    private static final class PartDigest extends OutputStream {
        private final MessageDigest digest;
        private long bytes;

        private PartDigest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override public void write(int value) {
            digest.update((byte) value);
            bytes = Math.addExact(bytes, 1);
        }

        @Override public void write(byte[] value, int offset, int length) {
            digest.update(value, offset, length);
            bytes = Math.addExact(bytes, length);
        }

        private long bytes() { return bytes; }
        private String sha256() { return java.util.HexFormat.of().formatHex(digest.digest()); }
    }
}
