package com.meetingautomation.api.processing;

import com.meetingautomation.api.audio.AudioNotAvailableException;
import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import com.meetingautomation.api.idempotency.IdempotencyKeyConflictException;
import com.meetingautomation.api.session.FailureFinalization;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.ProcessingFailure;
import com.meetingautomation.api.session.SessionStateConflictException;
import com.meetingautomation.api.session.SessionVersionConflictException;
import com.meetingautomation.document.MeetingDocumentRef;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class FailureFinalizeService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final MeetingSessionStore sessions;
    private final FailureDocumentWriter writer;
    private final AudioAssemblyUseCase audio;
    private final Clock clock;
    private final ConcurrentMap<Key, Accepted> accepted = new ConcurrentHashMap<>();

    @Autowired
    public FailureFinalizeService(MeetingSessionStore sessions, FailureDocumentWriter writer,
            AudioAssemblyUseCase audio) {
        this(sessions, writer, audio, Clock.systemUTC());
    }

    FailureFinalizeService(MeetingSessionStore sessions, FailureDocumentWriter writer,
            AudioAssemblyUseCase audio, Clock clock) {
        this.sessions = sessions;
        this.writer = writer;
        this.audio = audio;
        this.clock = clock;
    }

    public FailureFinalizeResponse finalizeFailure(String sessionId, String ifMatch, String idempotencyKey,
            FailureFinalizeRequest request) {
        int expectedVersion = parseVersion(ifMatch);
        String key = normalizeKey(idempotencyKey);
        String disposition = request == null ? null : request.audioDisposition();
        if (!"DOWNLOADED".equals(disposition) && !"DISCARDED".equals(disposition)) {
            throw new com.meetingautomation.api.error.ApiValidationException("audioDisposition");
        }
        return sessions.withSessionLock(sessionId, () -> {
            Key requestKey = new Key(sessionId, key);
            Accepted replay = accepted.get(requestKey);
            if (replay != null) {
                if (replay.expectedVersion() != expectedVersion || !replay.disposition().equals(disposition)) {
                    throw new IdempotencyKeyConflictException();
                }
                return replay.response();
            }
            MeetingSession current = sessions.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
            if (current.version() != expectedVersion) throw new SessionVersionConflictException();
            if (!"PROCESSING_FAILED".equals(current.status()) || current.processingFailure() == null) {
                throw new SessionStateConflictException();
            }
            if ("DOWNLOADED".equals(disposition) && !current.processingFailure().downloadCompleted()) {
                throw new AudioNotAvailableException();
            }
            MeetingDocumentRef document;
            try {
                document = writer.write(current, disposition);
            } catch (RuntimeException failedDocumentWrite) {
                throw new FailureDocumentWriteException();
            }
            if (document == null || document.documentId() == null || document.documentId().isBlank()) {
                throw new FailureDocumentWriteException();
            }
            FailureFinalization finalization = new FailureFinalization(disposition, clock.instant(), document);
            MeetingSession completed = sessions.completeProcessingFailure(sessionId, expectedVersion, finalization);
            try {
                audio.deleteAudio(sessionId);
            } catch (RuntimeException cleanupFailure) {
                try { audio.retainAudioUntil(sessionId, clock.instant()); }
                catch (RuntimeException ignored) { /* Durable cleanup scheduler will retry objects already listed. */ }
            }
            FailureFinalizeResponse response = new FailureFinalizeResponse(new FailureFinalizeResponse.Data(
                    sessionId, completed.status(), completed.version(), disposition,
                    document.documentId(), document.documentUrl()));
            accepted.put(requestKey, new Accepted(expectedVersion, disposition, response));
            return response;
        });
    }

    /** Finalizes an expired in-memory failure before the expiry sweep removes its last Audio object. */
    public boolean autoFinalizeExpired(String sessionId, java.time.Instant now) {
        return sessions.withSessionLock(sessionId, () -> {
            MeetingSession current = sessions.find(sessionId).orElse(null);
            if (current == null || !"PROCESSING_FAILED".equals(current.status())) return false;
            ProcessingFailure failure = current.processingFailure();
            if (failure == null || failure.audioExpiresAt() == null || failure.audioExpiresAt().isAfter(now)) {
                return false;
            }
            MeetingDocumentRef document;
            try {
                document = writer.write(current, "DISCARDED");
            } catch (RuntimeException failedDocumentWrite) {
                throw new FailureDocumentWriteException();
            }
            if (document == null || document.documentId() == null || document.documentId().isBlank()) {
                throw new FailureDocumentWriteException();
            }
            sessions.completeProcessingFailure(sessionId, current.version(),
                    new FailureFinalization("DISCARDED", now, document));
            try {
                audio.deleteAudio(sessionId);
            } catch (RuntimeException cleanupFailure) {
                try { audio.retainAudioUntil(sessionId, now); }
                catch (RuntimeException ignored) { /* The objects remain expired and the sweep will retry. */ }
            }
            return true;
        });
    }

    private static int parseVersion(String value) {
        if (value == null || !value.matches("\"[0-9]+\""))
            throw new com.meetingautomation.api.error.ApiValidationException("If-Match");
        try { return Integer.parseInt(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException failure) {
            throw new com.meetingautomation.api.error.ApiValidationException("If-Match");
        }
    }

    private static String normalizeKey(String value) {
        if (value == null || !KEY.matcher(value.trim()).matches()) {
            throw new com.meetingautomation.api.error.ApiValidationException("Idempotency-Key");
        }
        return value.trim();
    }

    private record Key(String sessionId, String value) { }
    private record Accepted(int expectedVersion, String disposition, FailureFinalizeResponse response) { }
}
