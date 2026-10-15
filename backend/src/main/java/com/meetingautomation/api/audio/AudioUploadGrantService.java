package com.meetingautomation.api.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingautomation.api.session.MeetingSessionService;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.UploadPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class AudioUploadGrantService {
    private final MeetingSessionStore sessions;
    private final MeetingSessionService meetingSessions;
    private final ObjectMapper json;
    private final String secret;
    private final Clock clock;

    @Autowired
    public AudioUploadGrantService(MeetingSessionStore sessions, MeetingSessionService meetingSessions, ObjectMapper json,
            @Value("${RENDER_AUDIO_GRANT_SECRET:}") String secret) {
        this(sessions, meetingSessions, json, secret, Clock.systemUTC());
    }

    AudioUploadGrantService(MeetingSessionStore sessions, MeetingSessionService meetingSessions, ObjectMapper json,
            String secret, Clock clock) {
        this.sessions = sessions;
        this.meetingSessions = meetingSessions;
        this.json = json;
        this.secret = secret;
        this.clock = clock;
    }

    public AudioUploadGrantResponse create(String sessionId, String rawSequence, String mimeType, long bytes, String sha256,
            String idempotencyKey) {
        return sessions.withSessionLock(sessionId, () -> createLocked(sessionId, rawSequence, mimeType, bytes, sha256, idempotencyKey));
    }

    private AudioUploadGrantResponse createLocked(String sessionId, String rawSequence, String mimeType, long bytes, String sha256,
            String idempotencyKey) {
        var session = sessions.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
        if (!"CREATED".equals(session.status()) && !"UPLOADING".equals(session.status())) {
            throw new com.meetingautomation.api.session.SessionStateConflictException();
        }
        if (secret == null || secret.isBlank()) throw new AudioStorageException();
        int sequence;
        try { sequence = Integer.parseInt(rawSequence); }
        catch (RuntimeException invalid) { throw new AudioChunkInvalidException(); }
        if (sequence < 0 || rawSequence == null || !rawSequence.matches("[0-9]+") || bytes < 1
                || sha256 == null || !sha256.matches("[a-f0-9]{64}") || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AudioChunkInvalidException();
        }
        String normalizedMime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        UploadPolicy policy = sessions.findUploadPolicy(sessionId).orElseGet(meetingSessions::uploadPolicy);
        if (!policy.acceptedMimeTypes().contains(normalizedMime) || bytes > policy.maxChunkBytes()) throw new AudioChunkInvalidException();
        long expiresAt = clock.millis() + 60_000;
        String pathname = "audio/" + sessionId + "/chunks/" + sequence;
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sessionId", sessionId);
            claims.put("sequence", sequence);
            claims.put("mimeType", normalizedMime);
            claims.put("bytes", bytes);
            claims.put("sha256", sha256);
            claims.put("expiresAt", expiresAt);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims));
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
            return new AudioUploadGrantResponse(new AudioUploadGrantResponse.Data(pathname, encoded + "." + signature, expiresAt));
        } catch (java.security.GeneralSecurityException | java.io.IOException failure) {
            throw new AudioStorageException();
        }
    }
}
