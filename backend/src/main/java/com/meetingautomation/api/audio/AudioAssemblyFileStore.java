package com.meetingautomation.api.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Filesystem adapter for ordered chunk access and atomically published assembled audio. */
public final class AudioAssemblyFileStore implements AudioAssemblyStorage {
    private static final int BUFFER_SIZE = 8192;

    private final Path root;
    private final AudioChunkFileStore chunkStore;

    @Autowired
    public AudioAssemblyFileStore(
            @Value("${TEMP_AUDIO_DIR:${java.io.tmpdir}/meeting-automation-audio}") String directory,
            AudioChunkFileStore chunkStore) {
        this(configuredRoot(directory), chunkStore);
    }

    AudioAssemblyFileStore(Path directory, AudioChunkFileStore chunkStore) {
        this.root = directory.toAbsolutePath().normalize();
        this.chunkStore = chunkStore;
    }

    @Override
    public List<Chunk> chunks(String sessionId) {
        return chunkStore.assemblyChunks(sessionId).stream()
                .map(chunk -> new Chunk(chunk.sequence(), chunk.mimeType(), chunk.bytes(), chunk.sha256()))
                .toList();
    }

    @Override
    public InputStream openChunk(String sessionId, int sequence) {
        return chunkStore.openChunk(sessionId, sequence);
    }

    @Override
    public OutputStream createPart(String sessionId) {
        Path sessionDirectory = sessionDirectory(sessionId);
        Path stagingDirectory = stagingDirectory(sessionId);
        try {
            ensureRootAndSession(sessionDirectory);
            deleteStaging(sessionId, stagingDirectory);
            Files.createDirectory(stagingDirectory);
            return Files.newOutputStream(partPath(sessionId), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (IOException | SecurityException failure) {
            try {
                deleteStaging(sessionId, stagingDirectory);
            } catch (IOException | RuntimeException ignored) {
                // The original storage failure is the useful signal to the caller.
            }
            throw new AudioAssemblyStorageException();
        }
    }

    @Override
    public Assembly publish(String sessionId, String mimeType, long bytes, String sha256) {
        Path stagingDirectory = stagingDirectory(sessionId);
        Path finalDirectory = assembledDirectory(sessionId);
        Path part = partPath(sessionId);
        Path stagedAudio = stagingDirectory.resolve("audio.bin");
        try {
            validateMetadata(mimeType, bytes, sha256);
            verifyDirectory(root);
            verifyDirectory(sessionDirectory(sessionId));
            verifyDirectory(stagingDirectory);
            verifyRegularFile(part);
            if (Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("An assembled output already exists.");
            }
            FileDigest actual = digestFile(part);
            if (actual.bytes() != bytes || !actual.sha256().equals(sha256)) {
                throw new IOException("Staged assembly content does not match metadata.");
            }
            Files.writeString(stagingDirectory.resolve("metadata"), serialize(mimeType, bytes, sha256),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.move(part, stagedAudio, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.move(stagingDirectory, finalDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic assembly publication is not supported.");
            }
            return findAssembly(sessionId).orElseThrow(AudioAssemblyStorageException::new);
        } catch (IOException | SecurityException failure) {
            throw new AudioAssemblyStorageException();
        }
    }

    @Override
    public Optional<Assembly> findAssembly(String sessionId) {
        Path sessionDirectory = sessionDirectory(sessionId);
        Path finalDirectory = assembledDirectory(sessionId);
        try {
            if (!existsOrThrowIfMissing(root)) return Optional.empty();
            verifyDirectory(root);
            if (!existsOrThrowIfMissing(sessionDirectory)) return Optional.empty();
            verifyDirectory(sessionDirectory);
            if (!existsOrThrowIfMissing(finalDirectory)) return Optional.empty();
            verifyDirectory(finalDirectory);

            Path audioFile = finalDirectory.resolve("audio.bin");
            Path metadataFile = finalDirectory.resolve("metadata");
            verifyRegularFile(audioFile);
            verifyRegularFile(metadataFile);
            AssemblyMetadata metadata = parse(Files.readString(metadataFile, StandardCharsets.UTF_8));
            FileDigest actual = digestFile(audioFile);
            if (actual.bytes() != metadata.bytes() || !actual.sha256().equals(metadata.sha256())) {
                throw new IOException("Published assembly does not match metadata.");
            }
            return Optional.of(new Assembly(audioFile.toAbsolutePath().normalize().toString(), metadata.mimeType(),
                    metadata.bytes(), metadata.sha256()));
        } catch (IOException | SecurityException failure) {
            throw new AudioAssemblyStorageException();
        }
    }

    @Override
    public void discardPart(String sessionId) {
        Path sessionDirectory = sessionDirectory(sessionId);
        Path stagingDirectory = stagingDirectory(sessionId);
        try {
            if (!existsOrThrowIfMissing(root)) return;
            verifyDirectory(root);
            if (!existsOrThrowIfMissing(sessionDirectory)) return;
            verifyDirectory(sessionDirectory);
            deleteStaging(sessionId, stagingDirectory);
        } catch (IOException | SecurityException failure) {
            throw new AudioAssemblyStorageException();
        }
    }

    @Override
    public void deleteSourceChunks(String sessionId) {
        chunkStore.deleteAssemblySources(sessionId);
    }

    @Override
    public InputStream openAssembly(Assembly assembly) {
        if (assembly == null || assembly.reference() == null) throw new AudioAssemblyStorageException();
        try {
            Path file = Path.of(assembly.reference());
            verifyRegularFile(file);
            return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException failure) {
            throw new AudioAssemblyStorageException();
        }
    }

    @Override
    public void deleteAssembly(String sessionId) {
        Path directory = assembledDirectory(sessionId);
        try {
            if (!existsOrThrowIfMissing(root)) return;
            verifyDirectory(root);
            if (!existsOrThrowIfMissing(directory)) return;
            verifyDirectory(directory);
            deleteTree(directory);
        } catch (IOException | SecurityException failure) {
            throw new AudioAssemblyStorageException();
        }
    }

    private void ensureRootAndSession(Path sessionDirectory) throws IOException {
        Files.createDirectories(root);
        verifyDirectory(root);
        Files.createDirectories(sessionDirectory);
        verifyDirectory(sessionDirectory);
    }

    private void deleteStaging(String sessionId, Path stagingDirectory) throws IOException {
        Path sessionDirectory = sessionDirectory(sessionId);
        verifyDirectory(root);
        verifyDirectory(sessionDirectory);
        if (!stagingDirectory.equals(stagingDirectory(sessionId))
                || !stagingDirectory.startsWith(sessionDirectory)) {
            throw new IOException("Assembly staging path is invalid.");
        }
        if (!Files.exists(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        verifyDirectory(stagingDirectory);
        deleteTree(stagingDirectory);
    }

    private Path sessionDirectory(String sessionId) {
        return root.resolve(sessionHash(sessionId));
    }

    private Path stagingDirectory(String sessionId) {
        return sessionDirectory(sessionId).resolve(".assembly-" + sessionHash(sessionId) + ".tmp");
    }

    private Path partPath(String sessionId) {
        return stagingDirectory(sessionId).resolve("." + sessionHash(sessionId) + ".part");
    }

    private Path assembledDirectory(String sessionId) {
        return sessionDirectory(sessionId).resolve("assembled");
    }

    private static String sessionHash(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new AudioAssemblyStorageException();
        return HexFormat.of().formatHex(newDigest().digest(sessionId.getBytes(StandardCharsets.UTF_8)));
    }

    private static void validateMetadata(String mimeType, long bytes, String sha256) throws IOException {
        if (mimeType == null || mimeType.isBlank() || mimeType.contains("\n") || mimeType.contains("\r")
                || bytes < 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Assembly metadata is invalid.");
        }
    }

    private static String serialize(String mimeType, long bytes, String sha256) {
        return mimeType + "\n" + bytes + "\n" + sha256 + "\n";
    }

    private static AssemblyMetadata parse(String value) throws IOException {
        String[] lines = value.split("\\n", -1);
        if (lines.length != 4 || !lines[3].isEmpty()) throw new IOException("Assembly metadata is invalid.");
        try {
            long bytes = Long.parseLong(lines[1]);
            validateMetadata(lines[0], bytes, lines[2]);
            return new AssemblyMetadata(lines[0], bytes, lines[2]);
        } catch (NumberFormatException invalid) {
            throw new IOException("Assembly metadata is invalid.");
        }
    }

    private static FileDigest digestFile(Path path) throws IOException {
        MessageDigest digest = newDigest();
        long count = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                count = Math.addExact(count, read);
                digest.update(buffer, 0, read);
            }
        } catch (ArithmeticException overflow) {
            throw new IOException("Assembly length exceeds supported bounds.");
        }
        return new FileDigest(count, HexFormat.of().formatHex(digest.digest()));
    }

    private static void verifyDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Assembly directory is invalid.");
        }
    }

    private static void verifyRegularFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Assembly file is invalid.");
        }
    }

    private static boolean existsOrThrowIfMissing(Path path) throws IOException {
        try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path configuredRoot(String directory) {
        if (directory == null || directory.isBlank()) throw new AudioAssemblyStorageException();
        try {
            return Path.of(directory);
        } catch (RuntimeException invalid) {
            throw new AudioAssemblyStorageException();
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record AssemblyMetadata(String mimeType, long bytes, String sha256) {
    }

    private record FileDigest(long bytes, String sha256) {
    }
}
