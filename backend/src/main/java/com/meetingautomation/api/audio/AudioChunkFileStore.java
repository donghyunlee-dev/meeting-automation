package com.meetingautomation.api.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stores each complete sequence as an atomically published directory beneath TEMP_AUDIO_DIR. */
@Component
public final class AudioChunkFileStore {
    private static final int BUFFER_SIZE = 8192;
    private static final Duration ABANDONED_UPLOAD_RETENTION = Duration.ofHours(24);
    static final String EXPIRY_FILE = ".expires-at";

    private final Path root;
    private final Clock clock;
    private final ConcurrentMap<ChunkKey, LockEntry> activeLocks = new ConcurrentHashMap<>();

    @Autowired
    AudioChunkFileStore(@Value("${TEMP_AUDIO_DIR:${java.io.tmpdir}/meeting-automation-audio}") String directory) {
        this(configuredRoot(directory), Clock.systemUTC());
    }

    AudioChunkFileStore(Path directory) {
        this(directory, Clock.systemUTC());
    }

    AudioChunkFileStore(Path directory, Clock clock) {
        this.root = directory.toAbsolutePath().normalize();
        this.clock = java.util.Objects.requireNonNull(clock);
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
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Audio storage root is invalid.");
            }
            Files.createDirectories(sessionDirectory);
            if (Files.isSymbolicLink(sessionDirectory)) throw new IOException("Storage directory is not usable.");
            temporaryDirectory = Files.createTempDirectory(sessionDirectory, ".upload-");
            ChunkMetadata incoming = writeAndValidate(temporaryDirectory, mimeType, declaredLength,
                    expectedSha256, maxChunkBytes, body);

            if (Files.exists(completedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                if (sameChunk(completedDirectory, incoming)) {
                    writeExpiry(sessionDirectory, clock.instant().plus(ABANDONED_UPLOAD_RETENTION));
                    return;
                }
                throw new AudioChunkConflictException();
            }

            try {
                Files.move(temporaryDirectory, completedDirectory, StandardCopyOption.ATOMIC_MOVE);
                temporaryDirectory = null;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic publication is not supported.");
            }
            writeExpiry(sessionDirectory, clock.instant().plus(ABANDONED_UPLOAD_RETENTION));
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

    public List<CompletedChunk> completedChunks(String sessionId) {
        return assemblyChunks(sessionId).stream()
                .map(chunk -> new CompletedChunk(chunk.sequence(), chunk.bytes()))
                .toList();
    }

    /** Returns trusted stored metadata for the assembly boundary; content digests are checked while streaming. */
    public List<StoredChunk> assemblyChunks(String sessionId) {
        Path sessionDirectory = root.resolve(sha256(sessionId.getBytes(StandardCharsets.UTF_8)));
        try {
            if (!existsOrThrowIfMissing(root)) return List.of();
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Audio storage root is invalid.");
            }
            if (!existsOrThrowIfMissing(sessionDirectory)) return List.of();
            if (Files.isSymbolicLink(sessionDirectory)
                    || !Files.isDirectory(sessionDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Audio session storage is invalid.");
            }

            TreeMap<Integer, StoredChunk> chunksBySequence = new TreeMap<>();
            try (var entries = Files.newDirectoryStream(sessionDirectory)) {
                for (Path entry : entries) {
                    Integer sequence = canonicalSequence(entry.getFileName().toString());
                    if (sequence == null) continue;
                    StoredChunk chunk = readCompletedChunk(entry, sequence);
                    if (chunksBySequence.putIfAbsent(sequence, chunk) != null) {
                        throw new IOException("Duplicate audio sequence metadata.");
                    }
                }
            }
            return List.copyOf(chunksBySequence.values());
        } catch (IOException | SecurityException failure) {
            throw new AudioStorageException();
        }
    }

    /** Opens one validated chunk at a time. The caller must close the returned stream before deleting sources. */
    public InputStream openChunk(String sessionId, int sequence) {
        Path sessionDirectory = root.resolve(sha256(sessionId.getBytes(StandardCharsets.UTF_8)));
        Path chunkDirectory = sessionDirectory.resolve(Integer.toString(sequence));
        Path audioFile = chunkDirectory.resolve("chunk.bin");
        try {
            if (sequence < 0 || !existsOrThrowIfMissing(root)
                    || Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !existsOrThrowIfMissing(sessionDirectory)
                    || !Files.isDirectory(sessionDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(sessionDirectory)
                    || !Files.isDirectory(chunkDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(chunkDirectory)) {
                throw new IOException("Completed chunk directory is invalid.");
            }
            readCompletedChunk(chunkDirectory, sequence);
            return Files.newInputStream(audioFile, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException failure) {
            throw new AudioStorageException();
        }
    }

    /** Removes only canonical sequence directories and preserves non-chunk data such as assembled output. */
    public void deleteAssemblySources(String sessionId) {
        Path sessionDirectory = root.resolve(sha256(sessionId.getBytes(StandardCharsets.UTF_8)));
        try {
            if (!existsOrThrowIfMissing(root)) return;
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Audio storage root is invalid.");
            }
            if (!existsOrThrowIfMissing(sessionDirectory)) return;
            if (Files.isSymbolicLink(sessionDirectory)
                    || !Files.isDirectory(sessionDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Audio session storage is invalid.");
            }
            try (var entries = Files.newDirectoryStream(sessionDirectory)) {
                for (Path entry : entries) {
                    if (canonicalSequence(entry.getFileName().toString()) != null) {
                        deleteTree(entry);
                    }
                }
            }
        } catch (IOException | SecurityException failure) {
            throw new AudioStorageException();
        }
    }

    private static boolean existsOrThrowIfMissing(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException missing) {
            Path ancestor = path.getParent();
            while (ancestor != null) {
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(ancestor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException missingAncestor) {
                    ancestor = ancestor.getParent();
                    continue;
                }
                if (attributes.isSymbolicLink()) {
                    try {
                        attributes = Files.readAttributes(ancestor, BasicFileAttributes.class);
                    } catch (NoSuchFileException brokenLink) {
                        throw new IOException("Audio storage path has a broken symbolic link.");
                    }
                }
                if (!attributes.isDirectory()) throw new NotDirectoryException(ancestor.toString());
                return false;
            }
            return false;
        }
    }

    private static StoredChunk readCompletedChunk(Path directory, int sequence) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Completed chunk directory is invalid.");
        }
        Path audioFile = directory.resolve("chunk.bin");
        Path metadataFile = directory.resolve("metadata");
        if (Files.isSymbolicLink(audioFile) || Files.isSymbolicLink(metadataFile)
                || !Files.isRegularFile(audioFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Completed chunk files are invalid.");
        }

        ChunkMetadata metadata = ChunkMetadata.parse(Files.readString(metadataFile, StandardCharsets.UTF_8));
        long actualLength = Files.size(audioFile);
        if (actualLength < 1 || actualLength != metadata.length()) {
            throw new IOException("Completed chunk length does not match its metadata.");
        }
        return new StoredChunk(sequence, metadata.mimeType(), actualLength, metadata.sha256());
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void writeExpiry(Path sessionDirectory, java.time.Instant expiresAt) throws IOException {
        Path temporary = Files.createTempFile(sessionDirectory, ".expiry-", ".tmp");
        try {
            Files.writeString(temporary, expiresAt.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temporary, sessionDirectory.resolve(EXPIRY_FILE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Atomic expiry publication is not supported.");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Integer canonicalSequence(String name) {
        if (!name.matches("0|[1-9][0-9]*")) return null;
        try {
            int sequence = Integer.parseInt(name);
            return sequence >= 0 ? sequence : null;
        } catch (NumberFormatException outsideRange) {
            return null;
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
            if (lines.length != 4 || !lines[3].isEmpty() || lines[0].isBlank() || lines[0].contains("\r")) {
                throw new IOException("Stored metadata is invalid.");
            }
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

    public record CompletedChunk(int sequence, long bytes) {
    }

    public record StoredChunk(int sequence, String mimeType, long bytes, String sha256) {
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
