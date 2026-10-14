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
    private final PrivateAudioStoragePort storage;

    public AudioChunkService(MeetingSessionStore sessionStore,
            MeetingSessionService meetingSessionService,
            PrivateAudioStoragePort storage) {
        this.sessionStore = sessionStore;
        this.meetingSessionService = meetingSessionService;
        this.storage = storage;
    }

    public AudioChunkUploadResponse upload(String sessionId, String rawSequence, String contentType,
            String rawSha256, String rawByteLength, String idempotencyKey,
            long contentLength, InputStream body) {
        return sessionStore.withSessionLock(sessionId, () -> uploadWhileSessionLocked(sessionId, rawSequence,
                contentType, rawSha256, rawByteLength, idempotencyKey, contentLength, body));
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

        var prior = storage.listForSession(sessionId).stream()
                .filter(object -> object.type() == PrivateAudioStoragePort.ObjectType.CHUNK
                        && object.sequence() == sequence)
                .findFirst();
        if (prior.isPresent()) {
            var object = prior.orElseThrow();
            if (object.bytes() != byteLength || !object.sha256().equals(sha256)
                    || !object.mimeType().equals(mimeType)) throw new AudioChunkConflictException();
        } else {
            storage.store(sessionId, PrivateAudioStoragePort.ObjectType.CHUNK, sequence, mimeType,
                    null, body, byteLength, sha256);
        }
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
