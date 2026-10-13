package com.meetingautomation.api.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stores each complete sequence as an atomically published directory beneath TEMP_AUDIO_DIR. */
@Component
final class AudioChunkFileStore {
    private static final int BUFFER_SIZE = 8192;

    private final Path root;
    private final ConcurrentMap<ChunkKey, LockEntry> activeLocks = new ConcurrentHashMap<>();

    @Autowired
    AudioChunkFileStore(@Value("${TEMP_AUDIO_DIR:${java.io.tmpdir}/meeting-automation-audio}") String directory) {
        this(configuredRoot(directory));
    }

    AudioChunkFileStore(Path directory) {
        this.root = directory.toAbsolutePath().normalize();
    }

    void store(String sessionId, int sequence, String mimeType, long declaredLength,
            String expectedSha256, int maxChunkBytes, InputStream body) {
        if (maxChunkBytes < 1) throw new AudioChunkInvalidException();
        String sessionKey = sha256(sessionId.getBytes(StandardCharsets.UTF_8));
        Path sessionDirectory = root.resolve(sessionKey);
        Path completedDirectory = sessionDirectory.resolve(Integer.toString(sequence));
        ChunkKey key = new ChunkKey(sessionKey, sequence);
        LockEntry lockEntry = acquire(key);
        lockEntry.lock.lock();
        Path temporaryDirectory = null;
        try {
            Files.createDirectories(sessionDirectory);
            if (Files.isSymbolicLink(sessionDirectory)) throw new IOException("Storage directory is not usable.");
            temporaryDirectory = Files.createTempDirectory(sessionDirectory, ".upload-");
            ChunkMetadata incoming = writeAndValidate(temporaryDirectory, mimeType, declaredLength,
                    expectedSha256, maxChunkBytes, body);

            if (Files.exists(completedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                if (sameChunk(completedDirectory, incoming)) return;
                throw new AudioChunkConflictException();
            }

            try {
                Files.move(temporaryDirectory, completedDirectory, StandardCopyOption.ATOMIC_MOVE);
                temporaryDirectory = null;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic publication is not supported.");
            }
        } catch (AudioChunkInvalidException | AudioChunkConflictException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new AudioStorageException();
        } finally {
            try {
                if (temporaryDirectory != null) deleteTemporaryDirectory(temporaryDirectory);
            } finally {
                release(key, lockEntry);
            }
        }
    }

    private LockEntry acquire(ChunkKey key) {
        return activeLocks.compute(key, (ignored, current) -> {
            LockEntry entry = current == null ? new LockEntry() : current;
            entry.references++;
            return entry;
        });
    }

    private void release(ChunkKey key, LockEntry entry) {
        entry.lock.unlock();
        activeLocks.computeIfPresent(key, (ignored, current) -> {
            if (current != entry) return current;
            entry.references--;
            return entry.references == 0 ? null : entry;
        });
    }

    private static ChunkMetadata writeAndValidate(Path directory, String mimeType, long declaredLength,
            String expectedSha256, int maxChunkBytes, InputStream body) throws IOException {
        long acceptedLimit = Math.min((long) maxChunkBytes, declaredLength);
        if (acceptedLimit < 1) throw new AudioChunkInvalidException();
        Path audioFile = directory.resolve("chunk.bin");
        MessageDigest digest = newDigest();
        long count = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(audioFile,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (true) {
                long remainingWithOverflowByte = acceptedLimit - count + 1;
                int requestedBytes = (int) Math.min(buffer.length, remainingWithOverflowByte);
                int read = body.read(buffer, 0, requestedBytes);
                if (read < 0) break;
                if (read == 0) {
                    int singleByte = body.read();
                    if (singleByte < 0) break;
                    buffer[0] = (byte) singleByte;
                    read = 1;
                }
                if (read > acceptedLimit - count) throw new AudioChunkInvalidException();
                count += read;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }

        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        if (count == 0 || count != declaredLength || !actualSha256.equals(expectedSha256)) {
            throw new AudioChunkInvalidException();
        }
        ChunkMetadata metadata = new ChunkMetadata(mimeType, count, actualSha256);
        Files.writeString(directory.resolve("metadata"), metadata.serialize(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return metadata;
    }

    private static boolean sameChunk(Path completedDirectory, ChunkMetadata incoming) throws IOException {
        if (Files.isSymbolicLink(completedDirectory) || !Files.isDirectory(completedDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Stored chunk directory is invalid.");
        }
        Path audioFile = completedDirectory.resolve("chunk.bin");
        Path metadataFile = completedDirectory.resolve("metadata");
        if (Files.isSymbolicLink(audioFile) || Files.isSymbolicLink(metadataFile)
                || !Files.isRegularFile(audioFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Stored chunk files are invalid.");
        }
        ChunkMetadata previous = ChunkMetadata.parse(Files.readString(metadataFile, StandardCharsets.UTF_8));
        ChunkMetadata actual = digestFile(audioFile, previous.mimeType());
        if (!previous.equals(actual)) throw new IOException("Stored chunk does not match its metadata.");
        return incoming.equals(actual);
    }

    private static ChunkMetadata digestFile(Path audioFile, String mimeType) throws IOException {
        MessageDigest digest = newDigest();
        long count = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(audioFile)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                count += read;
                digest.update(buffer, 0, read);
            }
        }
        return new ChunkMetadata(mimeType, count, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    private static Path configuredRoot(String directory) {
        if (directory == null || directory.isBlank()) throw new AudioStorageException();
        try {
            return Path.of(directory);
        } catch (RuntimeException invalid) {
            throw new AudioStorageException();
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            throw new AudioStorageException();
        }
    }

    private record ChunkMetadata(String mimeType, long length, String sha256) {
        String serialize() {
            return mimeType + "\n" + length + "\n" + sha256 + "\n";
        }

        static ChunkMetadata parse(String value) throws IOException {
            String[] lines = value.split("\\n", -1);
            if (lines.length != 4 || !lines[3].isEmpty()) throw new IOException("Stored metadata is invalid.");
            try {
                long length = Long.parseLong(lines[1]);
                if (length < 1 || !lines[2].matches("[0-9a-f]{64}")) throw new NumberFormatException();
                return new ChunkMetadata(lines[0], length, lines[2]);
            } catch (NumberFormatException invalid) {
                throw new IOException("Stored metadata is invalid.");
            }
        }
    }

    private record ChunkKey(String sessionHash, int sequence) {
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
