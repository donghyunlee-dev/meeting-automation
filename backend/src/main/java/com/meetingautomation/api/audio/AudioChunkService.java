package com.meetingautomation.api.audio;

import com.meetingautomation.api.session.MeetingSessionService;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.UploadPolicy;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public final class AudioChunkService {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+");

    private final MeetingSessionStore sessionStore;
    private final MeetingSessionService meetingSessionService;
    private final AudioChunkFileStore fileStore;

    public AudioChunkService(MeetingSessionStore sessionStore,
            MeetingSessionService meetingSessionService,
            AudioChunkFileStore fileStore) {
        this.sessionStore = sessionStore;
        this.meetingSessionService = meetingSessionService;
        this.fileStore = fileStore;
    }

    public AudioChunkUploadResponse upload(String sessionId, String rawSequence, String contentType,
            String rawSha256, String rawByteLength, String idempotencyKey,
            long contentLength, InputStream body) {
        return sessionStore.withSessionLock(sessionId, () -> uploadWhileSessionLocked(sessionId, rawSequence,
                contentType, rawSha256, rawByteLength, idempotencyKey, contentLength, body));
    }

    public AudioChunkUploadResponse confirmClientUpload(String sessionId, String rawSequence, String contentType,
            String rawSha256, String rawByteLength, String idempotencyKey) {
        return sessionStore.withSessionLock(sessionId, () -> confirmClientUploadLocked(sessionId, rawSequence, contentType,
                rawSha256, rawByteLength, idempotencyKey));
    }

    private AudioChunkUploadResponse confirmClientUploadLocked(String sessionId, String rawSequence, String contentType,
            String rawSha256, String rawByteLength, String idempotencyKey) {
        var session = sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
        if (!"CREATED".equals(session.status()) && !"UPLOADING".equals(session.status())) {
            throw new com.meetingautomation.api.session.SessionStateConflictException();
        }
        UploadPolicy policy = sessionStore.findUploadPolicy(sessionId).orElseGet(meetingSessionService::uploadPolicy);
        int sequence = parseSequence(rawSequence);
        String mimeType = validateMimeType(contentType, policy);
        String sha256 = validateSha256(rawSha256);
        long bytes = parseByteLength(rawByteLength);
        validateIdempotencyKey(idempotencyKey);
        if (bytes > policy.maxChunkBytes()) throw new AudioChunkInvalidException();

        if (!(storage instanceof ClientUploadedAudioStoragePort clientStorage)) throw new AudioStorageException();
        clientStorage.confirmClientUpload(sessionId, sequence, mimeType, bytes, sha256);
        Instant expiresAt = clock.instant().plus(PrivateAudioStoragePort.TEMPORARY_LEASE);
        renewUploadLease(sessionId, expiresAt);
        if ("CREATED".equals(session.status())) sessionStore.transitionStatus(sessionId, "CREATED", "UPLOADING");
        return new AudioChunkUploadResponse(new AudioChunkUploadResponse.Data(sequence, true));
    }


    private AudioChunkUploadResponse uploadWhileSessionLocked(String sessionId, String rawSequence, String contentType,
            String rawSha256, String rawByteLength, String idempotencyKey,
            long contentLength, InputStream body) {
        var session = sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
        if (!"CREATED".equals(session.status()) && !"UPLOADING".equals(session.status())) {
            throw new com.meetingautomation.api.session.SessionStateConflictException();
        }
        UploadPolicy policy = sessionStore.findUploadPolicy(sessionId).orElseGet(meetingSessionService::uploadPolicy);
        int sequence = parseSequence(rawSequence);
        String mimeType = validateMimeType(contentType, policy);
        String sha256 = validateSha256(rawSha256);
        long byteLength = parseByteLength(rawByteLength);
        validateIdempotencyKey(idempotencyKey);
        if (policy.maxChunkBytes() < 1 || byteLength > policy.maxChunkBytes()) {
            throw new AudioChunkInvalidException();
        }
        if (contentLength >= 0 && (contentLength != byteLength || contentLength > policy.maxChunkBytes())) {
            throw new AudioChunkInvalidException();
        }

        fileStore.store(sessionId, sequence, mimeType, byteLength, sha256,
                policy.maxChunkBytes(), body);
        if ("CREATED".equals(session.status())) {
            sessionStore.transitionStatus(sessionId, "CREATED", "UPLOADING");
        }
        return new AudioChunkUploadResponse(new AudioChunkUploadResponse.Data(sequence, true));
    }

    private static int parseSequence(String rawSequence) {
        if (rawSequence == null || !DECIMAL.matcher(rawSequence).matches()) {
            throw new AudioChunkInvalidException();
        }
        try {
            int sequence = Integer.parseInt(rawSequence);
            if (sequence < 0) throw new AudioChunkInvalidException();
            return sequence;
        } catch (NumberFormatException invalid) {
            throw new AudioChunkInvalidException();
        }
    }

    private static String validateMimeType(String contentType, UploadPolicy policy) {
        if (contentType == null) throw new AudioChunkInvalidException();
        String mimeType = contentType.trim().toLowerCase(Locale.ROOT);
        if (!policy.acceptedMimeTypes().contains(mimeType)) throw new AudioChunkInvalidException();
        return mimeType;
    }

    private static String validateSha256(String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) throw new AudioChunkInvalidException();
        return sha256;
    }

    private static long parseByteLength(String rawByteLength) {
        if (rawByteLength == null || !DECIMAL.matcher(rawByteLength).matches()) {
            throw new AudioChunkInvalidException();
        }
        try {
            long byteLength = Long.parseLong(rawByteLength);
            if (byteLength < 1) throw new AudioChunkInvalidException();
            return byteLength;
        } catch (NumberFormatException invalid) {
            throw new AudioChunkInvalidException();
        }
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new AudioChunkInvalidException();
    }
}
