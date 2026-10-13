package com.meetingautomation.api.processing;

import com.meetingautomation.api.audio.AudioAssemblyStorage;
import com.meetingautomation.api.session.UploadPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Revalidates a processing job and assembles its validated chunks into one temporary audio file. */
@Service
public final class AudioAssemblyUseCase {
    private static final int BUFFER_SIZE = 8192;
    private static final String STAGE = "AUDIO_ASSEMBLY";
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioAssemblyUseCase.class);

    private final AudioAssemblyStorage storage;
    private final int maxMeetingDurationMinutes;
    private final ConcurrentMap<String, LockEntry> activeLocks = new ConcurrentHashMap<>();

    public AudioAssemblyUseCase(
            AudioAssemblyStorage storage,
            @Value("${MAX_MEETING_DURATION_MINUTES:60}") int maxMeetingDurationMinutes) {
        this.storage = storage;
        this.maxMeetingDurationMinutes = maxMeetingDurationMinutes;
    }

    public AudioAssemblyStorage.Assembly assemble(ProcessingJob job, UploadPolicy policy, String traceId) {
        if (traceId == null || traceId.isBlank()) throw invalid("AUDIO_ASSEMBLY_INVALID_INPUT");
        String sessionId = job == null ? null : job.sessionId();
        try {
            validateJob(job, policy);
        } catch (AudioAssemblyException failure) {
            logFailure(sessionId, traceId, failure.code());
            throw failure;
        }
        LockEntry lockEntry = acquire(sessionId);
        lockEntry.lock.lock();
        try {
            return assembleWhileLocked(job, policy, traceId);
        } finally {
            release(sessionId, lockEntry);
        }
    }

    private AudioAssemblyStorage.Assembly assembleWhileLocked(ProcessingJob job, UploadPolicy policy,
            String traceId) {
        String mimeType = normalizeMimeType(job.mimeType());
        long maxAssemblyBytes = Math.multiplyExact((long) job.expectedChunks(), policy.maxChunkBytes());
        try {
            var existing = storage.findAssembly(job.sessionId());
            if (existing.isPresent()) {
                AudioAssemblyStorage.Assembly assembly = existing.orElseThrow();
                if (!mimeType.equals(assembly.mimeType()) || assembly.bytes() > maxAssemblyBytes) {
                    throw invalid("AUDIO_ASSEMBLY_OUTPUT_MISMATCH");
                }
                cleanupSources(job.sessionId(), traceId, true);
                logSuccess(job.sessionId(), traceId, assembly, true);
                return assembly;
            }

            storage.discardPart(job.sessionId());
            List<AudioAssemblyStorage.Chunk> chunks = storage.chunks(job.sessionId());
            validateChunks(chunks, job.expectedChunks(), mimeType, policy.maxChunkBytes(), maxAssemblyBytes);

            MessageDigest assembledDigest = newDigest();
            long totalBytes = 0;
            boolean published = false;
            try {
                try (OutputStream output = storage.createPart(job.sessionId())) {
                    for (int index = 0; index < chunks.size(); index++) {
                        totalBytes = copyAndVerifyChunk(job.sessionId(), chunks.get(index), index,
                                policy.maxChunkBytes(), maxAssemblyBytes, totalBytes, output, assembledDigest);
                    }
                }
                if (totalBytes < 1) throw invalid("AUDIO_ASSEMBLY_EMPTY");
                String assembledSha256 = HexFormat.of().formatHex(assembledDigest.digest());
                AudioAssemblyStorage.Assembly assembly = storage.publish(job.sessionId(), mimeType,
                        totalBytes, assembledSha256);
                published = true;
                cleanupSources(job.sessionId(), traceId, false);
                logSuccess(job.sessionId(), traceId, assembly, false);
                return assembly;
            } finally {
                if (!published) discardPart(job.sessionId(), traceId);
            }
        } catch (AudioAssemblyException failure) {
            logFailure(job.sessionId(), traceId, failure.code());
            throw failure;
        } catch (RuntimeException | IOException failure) {
            logFailure(job.sessionId(), traceId, "AUDIO_ASSEMBLY_FAILED");
            throw new AudioAssemblyException("AUDIO_ASSEMBLY_FAILED");
        }
    }

    private void validateJob(ProcessingJob job, UploadPolicy policy) {
        if (job == null || job.sessionId() == null || job.sessionId().isBlank()
                || job.expectedChunks() < 1 || job.durationMs() < 1 || maxMeetingDurationMinutes < 1
                || policy == null || policy.maxChunkBytes() < 1 || policy.chunkDurationSeconds() < 1
                || job.mimeType() == null || job.mimeType().isBlank()) {
            throw invalid("AUDIO_ASSEMBLY_INVALID_INPUT");
        }
        long maximumDurationMs = Math.multiplyExact((long) maxMeetingDurationMinutes, 60_000L);
        if (job.durationMs() > maximumDurationMs) throw invalid("AUDIO_ASSEMBLY_INVALID_INPUT");
        String mimeType = normalizeMimeType(job.mimeType());
        boolean accepted = policy.acceptedMimeTypes().stream()
                .filter(candidate -> candidate != null)
                .map(AudioAssemblyUseCase::normalizeMimeType)
                .anyMatch(mimeType::equals);
        if (!accepted) throw invalid("AUDIO_ASSEMBLY_INVALID_INPUT");
        Math.multiplyExact((long) job.expectedChunks(), policy.maxChunkBytes());
    }

    private static void validateChunks(List<AudioAssemblyStorage.Chunk> chunks, int expectedChunks,
            String mimeType, int maxChunkBytes, long maxAssemblyBytes) {
        if (chunks == null || chunks.size() != expectedChunks) {
            throw invalid("AUDIO_ASSEMBLY_CHUNKS_INCOMPLETE");
        }
        long declaredTotal = 0;
        for (int sequence = 0; sequence < expectedChunks; sequence++) {
            AudioAssemblyStorage.Chunk chunk = chunks.get(sequence);
            if (chunk == null || chunk.sequence() != sequence || chunk.mimeType() == null
                    || !mimeType.equals(normalizeMimeType(chunk.mimeType())) || chunk.bytes() < 1
                    || chunk.bytes() > maxChunkBytes || chunk.sha256() == null
                    || !chunk.sha256().matches("[0-9a-f]{64}")) {
                throw invalid("AUDIO_ASSEMBLY_CHUNK_INVALID");
            }
            declaredTotal = addWithinBound(declaredTotal, chunk.bytes(), maxAssemblyBytes);
        }
    }

    private long copyAndVerifyChunk(String sessionId, AudioAssemblyStorage.Chunk chunk, int expectedSequence,
            int maxChunkBytes, long maxAssemblyBytes, long previousTotalBytes, OutputStream output,
            MessageDigest assembledDigest) throws IOException {
        if (chunk.sequence() != expectedSequence) throw invalid("AUDIO_ASSEMBLY_CHUNK_INVALID");
        MessageDigest chunkDigest = newDigest();
        long chunkBytes = 0;
        long totalBytes = previousTotalBytes;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = storage.openChunk(sessionId, expectedSequence)) {
            while (true) {
                long remainingPlusOne = Math.min(chunk.bytes(), maxChunkBytes) - chunkBytes + 1;
                int requested = (int) Math.min(buffer.length, remainingPlusOne);
                int read = input.read(buffer, 0, requested);
                if (read < 0) break;
                if (read == 0) {
                    int singleByte = input.read();
                    if (singleByte < 0) break;
                    buffer[0] = (byte) singleByte;
                    read = 1;
                }
                long remainingAllowed = Math.min(chunk.bytes(), maxChunkBytes) - chunkBytes;
                if (read > remainingAllowed) throw invalid("AUDIO_ASSEMBLY_CHUNK_INVALID");
                chunkBytes = addWithinBound(chunkBytes, read, maxChunkBytes);
                totalBytes = addWithinBound(totalBytes, read, maxAssemblyBytes);
                chunkDigest.update(buffer, 0, read);
                assembledDigest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        String actualSha256 = HexFormat.of().formatHex(chunkDigest.digest());
        if (chunkBytes != chunk.bytes() || !actualSha256.equals(chunk.sha256())) {
            throw invalid("AUDIO_ASSEMBLY_CHUNK_INVALID");
        }
        return totalBytes;
    }

    private void cleanupSources(String sessionId, String traceId, boolean replay) {
        try {
            storage.deleteSourceChunks(sessionId);
        } catch (RuntimeException cleanupFailure) {
            LOGGER.atWarn()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("traceId", traceId)
                    .addKeyValue("stage", STAGE)
                    .addKeyValue("outcome", "source_cleanup_failed")
                    .addKeyValue("reusedOutput", replay)
                    .log("Assembled audio is valid; source chunk cleanup will be retried.");
        }
    }

    private void discardPart(String sessionId, String traceId) {
        try {
            storage.discardPart(sessionId);
        } catch (RuntimeException cleanupFailure) {
            LOGGER.atWarn()
                    .addKeyValue("sessionId", sessionId)
                    .addKeyValue("traceId", traceId)
                    .addKeyValue("stage", STAGE)
                    .addKeyValue("outcome", "partial_cleanup_failed")
                    .log("Partial audio assembly cleanup failed.");
        }
    }

    private static void logSuccess(String sessionId, String traceId, AudioAssemblyStorage.Assembly assembly,
            boolean reused) {
        LOGGER.atInfo()
                .addKeyValue("sessionId", sessionId)
                .addKeyValue("traceId", traceId)
                .addKeyValue("stage", STAGE)
                .addKeyValue("outcome", "succeeded")
                .addKeyValue("reusedOutput", reused)
                .addKeyValue("assembledBytes", assembly.bytes())
                .log("Audio assembly succeeded.");
    }

    private static void logFailure(String sessionId, String traceId, String errorCode) {
        LOGGER.atWarn()
                .addKeyValue("sessionId", sessionId)
                .addKeyValue("traceId", traceId)
                .addKeyValue("stage", STAGE)
                .addKeyValue("outcome", "failed")
                .addKeyValue("errorCode", errorCode)
                .log("Audio assembly failed.");
    }

    private LockEntry acquire(String sessionId) {
        return activeLocks.compute(sessionId, (ignored, current) -> {
            LockEntry entry = current == null ? new LockEntry() : current;
            entry.references++;
            return entry;
        });
    }

    private void release(String sessionId, LockEntry entry) {
        entry.lock.unlock();
        activeLocks.computeIfPresent(sessionId, (ignored, current) -> {
            if (current != entry) return current;
            entry.references--;
            return entry.references == 0 ? null : entry;
        });
    }

    private static String normalizeMimeType(String mimeType) {
        return mimeType.trim().toLowerCase(Locale.ROOT);
    }

    private static long addWithinBound(long current, long addition, long bound) {
        long total;
        try {
            total = Math.addExact(current, addition);
        } catch (ArithmeticException overflow) {
            throw invalid("AUDIO_ASSEMBLY_SIZE_LIMIT_EXCEEDED");
        }
        if (addition < 0 || total > bound) throw invalid("AUDIO_ASSEMBLY_SIZE_LIMIT_EXCEEDED");
        return total;
    }

    private static AudioAssemblyException invalid(String code) {
        return new AudioAssemblyException(code);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }
}
