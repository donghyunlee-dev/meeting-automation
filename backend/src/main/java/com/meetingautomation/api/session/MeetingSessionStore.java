package com.meetingautomation.api.session;

import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Process-local session storage. This implementation intentionally has no persistence. */
@Component
public final class MeetingSessionStore {
    private static final int SESSION_LOCK_STRIPE_COUNT = 64;
    private final Map<String, MeetingSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, DocumentConnectionGate.SessionLease> registrations = new ConcurrentHashMap<>();
    private final Map<String, UploadPolicy> uploadPolicies = new ConcurrentHashMap<>();
    private final ReentrantLock[] sessionLockStripes = createSessionLockStripes();

    public <T> T withSessionLock(String sessionId, Supplier<T> operation) {
        Objects.requireNonNull(sessionId, "sessionId");
        int stripe = Math.floorMod(sessionId.hashCode(), SESSION_LOCK_STRIPE_COUNT);
        ReentrantLock lock = sessionLockStripes[stripe];
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] createSessionLockStripes() {
        ReentrantLock[] stripes = new ReentrantLock[SESSION_LOCK_STRIPE_COUNT];
        for (int index = 0; index < stripes.length; index++) stripes[index] = new ReentrantLock();
        return stripes;
    }

    public synchronized MeetingSession save(MeetingSession session, DocumentConnectionGate.SessionLease registration) {
        return save(session, registration, null);
    }

    public synchronized MeetingSession save(
            MeetingSession session,
            DocumentConnectionGate.SessionLease registration,
            UploadPolicy uploadPolicy) {
        sessions.put(session.sessionId(), session);
        if (registration != null) registrations.put(session.sessionId(), registration);
        else registrations.remove(session.sessionId());
        if (uploadPolicy != null) uploadPolicies.put(session.sessionId(), uploadPolicy);
        else uploadPolicies.remove(session.sessionId());
        return session;
    }

    /** Removes an in-memory session and releases its shared document-connection registration. */
    public boolean remove(String sessionId) {
        return withSessionLock(sessionId, () -> {
            MeetingSession removed;
            DocumentConnectionGate.SessionLease registration;
            synchronized (this) {
                removed = sessions.remove(sessionId);
                registration = registrations.remove(sessionId);
                uploadPolicies.remove(sessionId);
            }
            if (registration != null) registration.close();
            return removed != null;
        });
    }

    public Set<String> sessionIds() { return Set.copyOf(sessions.keySet()); }

    public int size() {
        return sessions.size();
    }

    public Optional<MeetingSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public Optional<UploadPolicy> findUploadPolicy(String sessionId) {
        return Optional.ofNullable(uploadPolicies.get(sessionId));
    }

    public synchronized MeetingSession transitionStatus(String sessionId, String expectedStatus, String newStatus) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (!current.status().equals(expectedStatus)) throw new SessionStateConflictException();
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), newStatus,
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(),
                current.processingFailure());
        sessions.put(sessionId, updated);
        return updated;
    }

    /** Replaces the complete speaker mapping set with optimistic concurrency control. */
    public synchronized MeetingSession replaceSpeakerMappings(
            String sessionId, int expectedVersion, java.util.List<Speaker> replacementSpeakers) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (!"REVIEW".equals(current.status())) throw new SessionStateConflictException();
        if (current.version() != expectedVersion) throw new SessionVersionConflictException();
        if (replacementSpeakers == null || replacementSpeakers.size() != current.speakers().size()) {
            throw new IllegalArgumentException("Speaker mapping set is invalid.");
        }
        for (int index = 0; index < current.speakers().size(); index++) {
            Speaker before = current.speakers().get(index);
            Speaker replacement = replacementSpeakers.get(index);
            if (replacement == null || !before.speakerId().equals(replacement.speakerId())
                    || !before.label().equals(replacement.label())) {
                throw new IllegalArgumentException("Speaker mapping set is invalid.");
            }
        }
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), current.status(),
                current.processingStage(), current.progressPercent(), replacementSpeakers, current.transcript(),
                current.processingFailure());
        sessions.put(sessionId, updated);
        return updated;
    }

    /** Atomically replaces the complete Minutes snapshot using the caller's expected version. */
    public synchronized MeetingSession replaceMinutes(
            String sessionId, int expectedVersion, StructuredMinutes replacementMinutes) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (!"REVIEW".equals(current.status())) throw new SessionStateConflictException();
        if (current.version() != expectedVersion) throw new SessionVersionConflictException();
        if (replacementMinutes == null
                || !Objects.equals(current.meeting().templateId(), replacementMinutes.templateId())
                || !Objects.equals(current.meeting().templateVersion(), replacementMinutes.templateVersion())) {
            throw new ApiValidationException("templateId");
        }
        if (Objects.equals(current.minutes(), replacementMinutes)) return current;

        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), current.status(),
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(),
                current.processingFailure(), replacementMinutes);
        sessions.put(sessionId, updated);
        return updated;
    }

    public synchronized MeetingSession startProcessing(String sessionId) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (!"UPLOADING".equals(current.status())) throw new SessionStateConflictException();
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "PROCESSING",
                "AUDIO_ASSEMBLY", 0, current.speakers(), current.transcript(), null);
        sessions.put(sessionId, updated);
        return updated;
    }

    /** Restores the API-009 pre-queue snapshot when enqueue is rejected before the worker is visible. */
    public synchronized MeetingSession rollbackProcessingStart(
            String sessionId, int expectedVersion, MeetingSession previous) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (current.version() != expectedVersion || !"PROCESSING".equals(current.status())
                || !"AUDIO_ASSEMBLY".equals(current.processingStage()) || current.progressPercent() != 0
                || current.processingFailure() != null) {
            throw new SessionStateConflictException();
        }
        sessions.put(sessionId, previous);
        return previous;
    }

    public synchronized MeetingSession recordProcessingProgress(String sessionId, String stage, int progressPercent) {
        MeetingSession current = requireProcessing(sessionId);
        if (stage == null || !stage.matches("[A-Z][A-Z0-9_]*")
                || progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("Processing progress is invalid.");
        }
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), current.status(), stage,
                progressPercent, current.speakers(), current.transcript(), current.processingFailure());
        sessions.put(sessionId, updated);
        return updated;
    }

    /** Commits speakers and transcript in one session mutation after diarization has succeeded. */
    public synchronized MeetingSession saveDiarizationResult(
            String sessionId, java.util.List<Speaker> speakers, java.util.List<TranscriptSegment> transcript) {
        MeetingSession current = requireProcessing(sessionId);
        if (!"DIARIZATION".equals(current.processingStage()) || current.progressPercent() != 67
                || current.speakers().size() > 0 || current.transcript().size() > 0
                || speakers == null || transcript == null) {
            throw new IllegalArgumentException("Diarization result cannot be saved.");
        }
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), current.status(),
                "DIARIZATION", 100, speakers, transcript, null);
        sessions.put(sessionId, updated);
        return updated;
    }

    /** Commits a complete Minutes draft and the REVIEW transition as one synchronized mutation. */
    public synchronized MeetingSession saveMinutesAndComplete(String sessionId, StructuredMinutes minutes) {
        MeetingSession current = requireProcessing(sessionId);
        if (minutes == null || !"MINUTES_GENERATION".equals(current.processingStage())
                || current.progressPercent() != 100 || current.minutes() != null
                || !current.meeting().templateId().equals(minutes.templateId())
                || !Objects.equals(current.meeting().templateVersion(), minutes.templateVersion())
                || minutes.templateVersion() == null || minutes.templateVersion().isBlank()
                || minutes.summary() == null) {
            throw new IllegalArgumentException("Minutes result cannot be saved.");
        }
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "REVIEW",
                "DRAFT_READY", 100, current.speakers(), current.transcript(), null, minutes);
        sessions.put(sessionId, updated);
        return updated;
    }

    public synchronized MeetingSession failProcessing(
            String sessionId, String stage, int lastCompletedProgress, String errorCode, boolean retryable) {
        MeetingSession current = requireProcessing(sessionId);
        ProcessingFailure failure = new ProcessingFailure(stage, errorCode, retryable);
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "PROCESSING_FAILED",
                stage, lastCompletedProgress, current.speakers(), current.transcript(), failure);
        sessions.put(sessionId, updated);
        return updated;
    }

    private MeetingSession requireProcessing(String sessionId) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (!"PROCESSING".equals(current.status())) throw new SessionStateConflictException();
        return current;
    }

    private static MeetingSession copy(MeetingSession current, int version, String status,
            String processingStage, int progressPercent, java.util.List<Speaker> speakers,
            java.util.List<TranscriptSegment> transcript, ProcessingFailure failure) {
        return copy(current, version, status, processingStage, progressPercent,
                speakers, transcript, failure, current.minutes());
    }

    private static MeetingSession copy(MeetingSession current, int version, String status,
            String processingStage, int progressPercent, java.util.List<Speaker> speakers,
            java.util.List<TranscriptSegment> transcript, ProcessingFailure failure, StructuredMinutes minutes) {
        return new MeetingSession(current.sessionId(), version, status, current.documentConnectionVersion(),
                current.meeting(), processingStage, progressPercent, speakers, transcript, failure, minutes);
    }
}
