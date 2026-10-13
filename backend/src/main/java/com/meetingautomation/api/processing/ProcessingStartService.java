package com.meetingautomation.api.processing;

import com.meetingautomation.api.audio.AudioChunkFileStore;
import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.api.idempotency.IdempotencyKeyConflictException;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.SessionStateConflictException;
import com.meetingautomation.api.session.UploadPolicy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class ProcessingStartService {
    private final MeetingSessionStore sessionStore;
    private final AudioChunkFileStore chunkStore;
    private final ProcessingJobQueue queue;
    private final int maxMeetingDurationMinutes;
    private final ConcurrentMap<RequestKey, RequestRecord> requests = new ConcurrentHashMap<>();

    public ProcessingStartService(
            MeetingSessionStore sessionStore,
            AudioChunkFileStore chunkStore,
            ProcessingJobQueue queue,
            @Value("${MAX_MEETING_DURATION_MINUTES:60}") int maxMeetingDurationMinutes) {
        this.sessionStore = sessionStore;
        this.chunkStore = chunkStore;
        this.queue = queue;
        this.maxMeetingDurationMinutes = maxMeetingDurationMinutes;
    }

    public ProcessingStartResponse start(String sessionId, String idempotencyKey, ProcessingStartRequest request) {
        return sessionStore.withSessionLock(sessionId,
                () -> startWhileLocked(sessionId, idempotencyKey, request));
    }

    private ProcessingStartResponse startWhileLocked(
            String sessionId, String idempotencyKey, ProcessingStartRequest request) {
        MeetingSession session = sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
        Command command = validate(sessionId, idempotencyKey, request);
        RequestKey key = new RequestKey(sessionId, command.idempotencyKey());
        RequestRecord previous = requests.get(key);
        if (previous != null) {
            if (!previous.command().equals(command)) throw new IdempotencyKeyConflictException();
            return previous.response();
        }
        if (!"UPLOADING".equals(session.status())) throw new SessionStateConflictException();

        UploadPolicy policy = sessionStore.findUploadPolicy(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session upload policy is unavailable."));
        validateCompletedChunks(sessionId, command.expectedChunks(), policy);

        queue.enqueue(new ProcessingJob(sessionId, command.expectedChunks(), command.durationMs(), command.mimeType()));
        sessionStore.transitionStatus(sessionId, "UPLOADING", "PROCESSING");
        ProcessingStartResponse response = new ProcessingStartResponse(
                new ProcessingStartResponse.Data(sessionId, "PROCESSING", "AUDIO_ASSEMBLY"));
        requests.put(key, new RequestRecord(command, response));
        return response;
    }

    private Command validate(String sessionId, String idempotencyKey, ProcessingStartRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiValidationException("Idempotency-Key");
        }
        if (request == null) throw new ApiValidationException("request");
        int expectedChunks = exactInteger(request.expectedChunks(), "expectedChunks");
        if (expectedChunks < 1) {
            throw new ApiValidationException("expectedChunks");
        }
        long durationMs = exactLong(request.durationMs(), "durationMs");
        long maxDurationMs = (long) maxMeetingDurationMinutes * 60_000L;
        if (durationMs < 1 || durationMs > maxDurationMs) {
            throw new ApiValidationException("durationMs");
        }
        if (request.mimeType() == null || request.mimeType().isBlank()) {
            throw new ApiValidationException("mimeType");
        }
        String mimeType = request.mimeType().trim().toLowerCase(Locale.ROOT);
        UploadPolicy policy = sessionStore.findUploadPolicy(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session upload policy is unavailable."));
        boolean acceptedMime = policy.acceptedMimeTypes().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(mimeType::equals);
        if (!acceptedMime) throw new ApiValidationException("mimeType");
        return new Command(idempotencyKey.trim(), expectedChunks, durationMs, mimeType);
    }

    private static int exactInteger(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ApiValidationException(field);
        }
        return value.intValue();
    }

    private static long exactLong(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ApiValidationException(field);
        }
        return value.longValue();
    }

    private void validateCompletedChunks(String sessionId, int expectedChunks, UploadPolicy policy) {
        List<AudioChunkFileStore.CompletedChunk> chunks = chunkStore.completedChunks(sessionId);
        if (chunks.size() != expectedChunks) throw new AudioChunksIncompleteException();
        for (int sequence = 0; sequence < expectedChunks; sequence++) {
            AudioChunkFileStore.CompletedChunk chunk = chunks.get(sequence);
            if (chunk.sequence() != sequence || chunk.bytes() < 1 || chunk.bytes() > policy.maxChunkBytes()) {
                throw new AudioChunksIncompleteException();
            }
        }
    }

    private record Command(String idempotencyKey, int expectedChunks, long durationMs, String mimeType) {
    }

    private record RequestKey(String sessionId, String idempotencyKey) {
    }

    private record RequestRecord(Command command, ProcessingStartResponse response) {
    }
}
