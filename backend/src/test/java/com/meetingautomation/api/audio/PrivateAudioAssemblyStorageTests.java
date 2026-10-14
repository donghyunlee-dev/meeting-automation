package com.meetingautomation.api.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PrivateAudioAssemblyStorageTests {
    private static final int LARGE_BYTES = 16 * 1024 * 1024;
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void assemblesPrivateChunksIntoOpaqueDurableHandleAndCanReopenStream() throws Exception {
        TestPrivateAudioStorage objectStore = new TestPrivateAudioStorage();
        byte[] first = {1, 2};
        byte[] second = {3, 4, 5};
        objectStore.store("ms_synthetic", PrivateAudioStoragePort.ObjectType.CHUNK, 0, "audio/webm", null,
                new ByteArrayInputStream(first), first.length, sha256(first));
        objectStore.store("ms_synthetic", PrivateAudioStoragePort.ObjectType.CHUNK, 1, "audio/webm", null,
                new ByteArrayInputStream(second), second.length, sha256(second));
        PrivateAudioAssemblyStorage storage = new PrivateAudioAssemblyStorage(objectStore,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        AudioAssemblyStorage.Assembly assembly;
        try (var output = storage.createPart("ms_synthetic")) {
            output.write(first);
            output.write(second);
        }
        assembly = storage.publish("ms_synthetic", "audio/webm", 5, sha256(new byte[] {1, 2, 3, 4, 5}));

        assertThat(assembly.reference()).isNotBlank().doesNotContain("ms_synthetic", "s3://", "/");
        assertThat(storage.findAssembly("ms_synthetic")).contains(assembly);
        try (InputStream input = storage.openAssembly(assembly)) {
            assertThat(input.readAllBytes()).containsExactly(1, 2, 3, 4, 5);
        }
        assertThat(objectStore.listForSession("ms_synthetic"))
                .filteredOn(object -> object.type() == PrivateAudioStoragePort.ObjectType.ASSEMBLED)
                .singleElement()
                .satisfies(object -> assertThat(object.expiresAt()).isEqualTo(NOW.plusSeconds(24 * 60 * 60)));
    }

    @Test
    void rejectsAssemblyMetadataThatDoesNotMatchStagedAudioAndKeepsChunks() throws Exception {
        TestPrivateAudioStorage objectStore = new TestPrivateAudioStorage();
        byte[] chunk = {7, 8, 9};
        objectStore.store("ms_synthetic", PrivateAudioStoragePort.ObjectType.CHUNK, 0, "audio/webm", null,
                new ByteArrayInputStream(chunk), chunk.length, sha256(chunk));
        PrivateAudioAssemblyStorage storage = new PrivateAudioAssemblyStorage(objectStore);
        try (var output = storage.createPart("ms_synthetic")) { output.write(chunk); }

        assertThatThrownBy(() -> storage.publish("ms_synthetic", "audio/webm", 3, "0".repeat(64)))
                .isInstanceOf(AudioAssemblyStorageException.class);
        assertThat(objectStore.listForSession("ms_synthetic"))
                .filteredOn(object -> object.type() == PrivateAudioStoragePort.ObjectType.CHUNK).hasSize(1);
    }

    @Test
    void appliesAndClearsDurableExpiryForEveryObjectInASession() throws Exception {
        TestPrivateAudioStorage objectStore = new TestPrivateAudioStorage();
        byte[] audio = {5, 4, 3};
        objectStore.store("ms_expiry", PrivateAudioStoragePort.ObjectType.CHUNK, 0, "audio/webm", null,
                new ByteArrayInputStream(audio), audio.length, sha256(audio));
        PrivateAudioAssemblyStorage storage = new PrivateAudioAssemblyStorage(objectStore);
        try (var part = storage.createPart("ms_expiry")) { part.write(audio); }
        storage.publish("ms_expiry", "audio/webm", audio.length, sha256(audio));
        Instant expiry = Instant.parse("2026-10-15T00:00:00Z");

        storage.setSessionExpiry("ms_expiry", expiry);
        assertThat(objectStore.listForSession("ms_expiry")).allSatisfy(object ->
                assertThat(object.expiresAt()).isEqualTo(expiry));

        storage.setSessionExpiry("ms_expiry", null);
        assertThat(objectStore.listForSession("ms_expiry")).allSatisfy(object ->
                assertThat(object.expiresAt()).isNull());
    }

    @Test
    void streamsLargeAssemblyWithBoundedReadBuffersInsteadOfRetainingWholeAudio() throws Exception {
        StreamingStorage objectStore = new StreamingStorage(LARGE_BYTES);
        PrivateAudioAssemblyStorage storage = new PrivateAudioAssemblyStorage(objectStore);
        byte[] buffer = new byte[8192];
        try (OutputStream part = storage.createPart("ms_large")) {
            for (int offset = 0; offset < LARGE_BYTES; offset += buffer.length) {
                int length = Math.min(buffer.length, LARGE_BYTES - offset);
                for (int i = 0; i < length; i++) buffer[i] = (byte) ((offset + i) * 31);
                part.write(buffer, 0, length);
            }
        }

        AudioAssemblyStorage.Assembly result = storage.publish("ms_large", "audio/webm", LARGE_BYTES,
                objectStore.expectedSha256());

        assertThat(result.bytes()).isEqualTo(LARGE_BYTES);
        assertThat(objectStore.storedBytes).isEqualTo(LARGE_BYTES);
        assertThat(objectStore.maxReadBuffer).isLessThanOrEqualTo(8192);
    }

    @Test
    void opensSourceChunkStreamsLazilySoOnlyOneObjectConnectionIsHeldAtATime() throws Exception {
        SequentialObjectStorage objectStore = new SequentialObjectStorage(List.of(
                new byte[] {1, 2}, new byte[] {3, 4}, new byte[] {5, 6}));
        PrivateAudioAssemblyStorage storage = new PrivateAudioAssemblyStorage(objectStore);
        byte[] complete = {1, 2, 3, 4, 5, 6};
        try (OutputStream part = storage.createPart("ms_sequential")) { part.write(complete); }

        AudioAssemblyStorage.Assembly result = storage.publish("ms_sequential", "audio/webm", complete.length,
                sha256(complete));

        assertThat(result.bytes()).isEqualTo(complete.length);
        assertThat(objectStore.maximumConcurrentReads).isEqualTo(1);
        assertThat(objectStore.storedAudio).containsExactly(complete);
    }

    private static final class StreamingStorage implements PrivateAudioStoragePort {
        private final int size;
        private final String sourceSha;
        private final StoredObject chunk;
        private long storedBytes;
        private int maxReadBuffer;

        private StreamingStorage(int size) throws Exception {
            this.size = size;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int offset = 0; offset < size; offset += buffer.length) {
                int length = Math.min(buffer.length, size - offset);
                for (int i = 0; i < length; i++) buffer[i] = (byte) ((offset + i) * 31);
                digest.update(buffer, 0, length);
            }
            sourceSha = HexFormat.of().formatHex(digest.digest());
            chunk = new StoredObject("opaque-chunk", "ms_large", ObjectType.CHUNK, 0, "audio/webm",
                    size, sourceSha, null);
        }

        private String expectedSha256() { return sourceSha; }
        @Override public StoredObject store(String sessionId, ObjectType type, int sequence, String mimeType,
                java.time.Instant expiresAt, InputStream body, long bytes, String sha256) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = body.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    maxReadBuffer = Math.max(maxReadBuffer, buffer.length);
                    storedBytes += count;
                    digest.update(buffer, 0, count);
                }
                if (storedBytes != bytes || !HexFormat.of().formatHex(digest.digest()).equals(sha256)) {
                    throw new IllegalArgumentException("Checksum mismatch");
                }
                return new StoredObject("opaque-assembly", sessionId, type, sequence, mimeType, bytes, sha256,
                        expiresAt);
            } catch (IOException | NoSuchAlgorithmException failure) {
                throw new IllegalStateException(failure);
            }
        }
        @Override public InputStream open(String objectId) {
            return new InputStream() {
                private int offset;
                @Override public int read() { return offset == size ? -1 : (byte) (offset++ * 31) & 0xff; }
                @Override public int read(byte[] bytes, int start, int length) {
                    if (offset == size) return -1;
                    int count = Math.min(length, size - offset);
                    for (int i = 0; i < count; i++) bytes[start + i] = (byte) ((offset + i) * 31);
                    offset += count;
                    return count;
                }
            };
        }
        @Override public Optional<StoredObject> find(String objectId) { return Optional.empty(); }
        @Override public List<StoredObject> listForSession(String sessionId) { return List.of(chunk); }
        @Override public List<StoredObject> listExpired(java.time.Instant now) { return List.of(); }
        @Override public void extendExpiry(String objectId, java.time.Instant expiresAt) { }
        @Override public void delete(String objectId) { }
    }

    private static final class SequentialObjectStorage implements PrivateAudioStoragePort {
        private final List<byte[]> chunks;
        private final List<StoredObject> metadata;
        private int activeReads;
        private int maximumConcurrentReads;
        private byte[] storedAudio;

        private SequentialObjectStorage(List<byte[]> chunks) throws Exception {
            this.chunks = chunks;
            var values = new java.util.ArrayList<StoredObject>();
            for (int sequence = 0; sequence < chunks.size(); sequence++) {
                byte[] bytes = chunks.get(sequence);
                values.add(new StoredObject("chunk-" + sequence, "ms_sequential", ObjectType.CHUNK, sequence,
                        "audio/webm", bytes.length, sha256(bytes), null));
            }
            metadata = values;
        }

        @Override public StoredObject store(String sessionId, ObjectType type, int sequence, String mimeType,
                Instant expiresAt, InputStream body, long bytes, String sha256) {
            try {
                storedAudio = body.readAllBytes();
                if (storedAudio.length != bytes || !sha256Unchecked(storedAudio).equals(sha256)) {
                    throw new IllegalArgumentException("Unexpected assembled audio bytes");
                }
                var assembled = new StoredObject("assembled", sessionId, type, sequence, mimeType, bytes, sha256,
                        expiresAt);
                metadata.add(assembled);
                return assembled;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override public InputStream open(String objectId) {
            int sequence = Integer.parseInt(objectId.substring("chunk-".length()));
            activeReads++;
            maximumConcurrentReads = Math.max(maximumConcurrentReads, activeReads);
            return new ByteArrayInputStream(chunks.get(sequence)) {
                @Override public void close() throws IOException {
                    activeReads--;
                    super.close();
                }
            };
        }

        @Override public Optional<StoredObject> find(String objectId) { return Optional.empty(); }
        @Override public List<StoredObject> listForSession(String sessionId) { return metadata.stream()
                .filter(object -> object.sessionId().equals(sessionId)).toList(); }
        @Override public List<StoredObject> listExpired(Instant now) { return List.of(); }
        @Override public void extendExpiry(String objectId, Instant expiresAt) { }
        @Override public void delete(String objectId) { }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256Unchecked(byte[] bytes) {
        try { return sha256(bytes); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
