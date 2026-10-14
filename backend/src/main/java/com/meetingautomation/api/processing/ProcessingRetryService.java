package com.meetingautomation.api.processing;

import com.meetingautomation.api.audio.AudioNotAvailableException;
import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import com.meetingautomation.api.idempotency.IdempotencyKeyConflictException;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.ProcessingFailure;
import com.meetingautomation.api.session.SessionStateConflictException;
import com.meetingautomation.api.session.SessionVersionConflictException;
import com.meetingautomation.api.session.UploadPolicy;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class ProcessingRetryService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final MeetingSessionStore sessions;
    private final ProcessingJobQueue queue;
    private final AudioAssemblyUseCase audio;
    private final Clock clock;
    private final ConcurrentMap<Key, AcceptedRetry> accepted = new ConcurrentHashMap<>();

    @Autowired
    public ProcessingRetryService(MeetingSessionStore sessions, ProcessingJobQueue queue, AudioAssemblyUseCase audio) {
        this(sessions, queue, audio, Clock.systemUTC());
    }

    ProcessingRetryService(MeetingSessionStore sessions, ProcessingJobQueue queue, AudioAssemblyUseCase audio,
            Clock clock) {
        this.sessions = sessions;
        this.queue = queue;
        this.audio = audio;
        this.clock = clock;
    }

    public ProcessingStartResponse retry(String sessionId, String ifMatch, String idempotencyKey) {
        int expectedVersion = parseVersion(ifMatch);
        String key = normalizeKey(idempotencyKey);
        return sessions.withSessionLock(sessionId, () -> {
            Key requestKey = new Key(sessionId, key);
            AcceptedRetry previousRequest = accepted.get(requestKey);
            if (previousRequest != null) {
                if (previousRequest.expectedVersion() != expectedVersion) throw new IdempotencyKeyConflictException();
                return previousRequest.response();
            }
            MeetingSession current = sessions.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
            if (!"PROCESSING_FAILED".equals(current.status()) || current.processingFailure() == null) {
                throw new SessionStateConflictException();
            }
            if (current.version() != expectedVersion) throw new SessionVersionConflictException();
            ProcessingFailure failure = current.processingFailure();
            if (!failure.retryable()) throw new SessionStateConflictException();
            if (failure.audioExpiresAt() == null || !failure.audioExpiresAt().isAfter(clock.instant())) {
                throw new AudioNotAvailableException();
            }
            ProcessingJob job = sessions.findProcessingJob(sessionId).orElseThrow(AudioNotAvailableException::new);
            UploadPolicy policy = sessions.findUploadPolicy(sessionId).orElseThrow(AudioNotAvailableException::new);
            if ("AUDIO_ASSEMBLY".equals(failure.stage())) {
                if (!failure.retryInputAvailable() || !audio.hasRetryInputs(job, policy)) {
                    throw new AudioNotAvailableException();
                }
            } else if (audio.findAssembly(sessionId).isEmpty()) {
                throw new AudioNotAvailableException();
            }
            audio.retainAudioUntil(sessionId, null);
            MeetingSession retrying = sessions.startProcessingRetry(sessionId);
            try {
                queue.enqueue(job);
            } catch (RuntimeException rejected) {
                sessions.rollbackProcessingRetry(sessionId, retrying.version(), current);
                audio.retainAudioUntil(sessionId, failure.audioExpiresAt());
                throw rejected;
            }
            ProcessingStartResponse response = new ProcessingStartResponse(
                    new ProcessingStartResponse.Data(sessionId, "PROCESSING", failure.stage()));
            accepted.put(requestKey, new AcceptedRetry(expectedVersion, response));
            return response;
        });
    }

    private static int parseVersion(String value) {
        if (value == null || !value.matches("\"[0-9]+\"")) throw new com.meetingautomation.api.error.ApiValidationException("If-Match");
        try { return Integer.parseInt(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException failure) { throw new com.meetingautomation.api.error.ApiValidationException("If-Match"); }
    }

    private static String normalizeKey(String value) {
        if (value == null || !KEY.matcher(value.trim()).matches()) {
            throw new com.meetingautomation.api.error.ApiValidationException("Idempotency-Key");
        }
        return value.trim();
    }

    private record Key(String sessionId, String idempotencyKey) { }
    private record AcceptedRetry(int expectedVersion, ProcessingStartResponse response) { }
}
