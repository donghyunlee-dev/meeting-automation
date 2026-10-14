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
    private final Map<String, MinutesRegenerationBackup> minutesRegenerationBackups = new ConcurrentHashMap<>();
    private final Map<String, MeetingSessionStatusResponse.LastOperation> lastOperations = new ConcurrentHashMap<>();
    private final Map<String, AcceptedReviewConfirmation> acceptedReviewConfirmations = new java.util.HashMap<>();
    private final Map<String, AcceptedPublish> acceptedPublishes = new java.util.HashMap<>();
    private final Map<String, PublishSnapshot> publishSnapshots = new ConcurrentHashMap<>();
    private final Map<String, MeetingSession> publishPreAcceptance = new ConcurrentHashMap<>();
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
        minutesRegenerationBackups.remove(session.sessionId());
        lastOperations.remove(session.sessionId());
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
                minutesRegenerationBackups.remove(sessionId);
                lastOperations.remove(sessionId);
                acceptedReviewConfirmations.entrySet().removeIf(entry ->
                        entry.getValue().fingerprint().sessionId().equals(sessionId));
                acceptedPublishes.entrySet().removeIf(entry -> entry.getValue().fingerprint().sessionId().equals(sessionId));
                publishSnapshots.remove(sessionId);
                publishPreAcceptance.remove(sessionId);
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

    /** Confirms one valid Review snapshot and stores its idempotent response in the same commit boundary. */
    public ReviewConfirmationResponse confirmReview(
            String sessionId,
            int expectedVersion,
            String idempotencyKey,
            ReviewConfirmationService.ReviewConfirmationFingerprint fingerprint,
            ReviewConfirmationValidator validator) {
        return withSessionLock(sessionId, () -> confirmReviewLocked(
                sessionId, expectedVersion, idempotencyKey, fingerprint, validator));
    }

    private synchronized ReviewConfirmationResponse confirmReviewLocked(
            String sessionId,
            int expectedVersion,
            String idempotencyKey,
            ReviewConfirmationService.ReviewConfirmationFingerprint fingerprint,
            ReviewConfirmationValidator validator) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new com.meetingautomation.api.audio.AudioSessionNotFoundException();

        AcceptedReviewConfirmation accepted = acceptedReviewConfirmations.get(idempotencyKey);
        if (accepted != null) {
            if (!accepted.fingerprint().equals(fingerprint)) {
                throw new com.meetingautomation.api.idempotency.IdempotencyKeyConflictException();
            }
            return accepted.response();
        }
        if (!"REVIEW".equals(current.status())) throw new SessionStateConflictException();
        if (current.version() != expectedVersion) throw new SessionVersionConflictException();

        java.util.List<ReviewValidationIssue> issues = validator.validate(current);
        if (!issues.isEmpty()) throw new ReviewValidationException(issues);

        MeetingSession confirmed = new MeetingSession(current.sessionId(), Math.addExact(current.version(), 1),
                "CONFIRMED", current.documentConnectionVersion(), current.meeting(), current.processingStage(),
                current.progressPercent(), current.speakers(), current.transcript(),
                current.processingFailure(), current.minutes(), current.createdAt());
        ReviewConfirmationResponse response = ReviewConfirmationResponse.confirmed(confirmed);
        sessions.put(sessionId, confirmed);
        acceptedReviewConfirmations.put(idempotencyKey,
                new AcceptedReviewConfirmation(fingerprint, response));
        return response;
    }

    private record AcceptedReviewConfirmation(
            ReviewConfirmationService.ReviewConfirmationFingerprint fingerprint,
            ReviewConfirmationResponse response) { }

    public synchronized PublishAcceptance acceptPublish(String sessionId, int expectedVersion, String key,
            PublishFingerprint fingerprint, java.util.List<String> recipients, boolean notificationEnabled) {
        AcceptedPublish prior = acceptedPublishes.get(key);
        if (prior != null) {
            if (!prior.fingerprint().equals(fingerprint))
                throw new com.meetingautomation.api.idempotency.IdempotencyKeyConflictException();
            return new PublishAcceptance(prior.response(), null, false);
        }
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new com.meetingautomation.api.audio.AudioSessionNotFoundException();
        if (!("CONFIRMED".equals(current.status()) || "DOCUMENT_FAILED".equals(current.status())))
            throw new SessionStateConflictException();
        if (current.version() != expectedVersion) throw new SessionVersionConflictException();
        MeetingSession publishing = copy(current, Math.addExact(current.version(), 1), "PUBLISHING",
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(),
                current.processingFailure());
        sessions.put(sessionId, publishing);
        publishPreAcceptance.put(sessionId, current);
        PublishSnapshot snapshot = new PublishSnapshot(java.util.List.copyOf(recipients), notificationEnabled, null, null);
        publishSnapshots.put(sessionId, snapshot);
        PublishResponse response = PublishResponse.accepted(sessionId);
        acceptedPublishes.put(key, new AcceptedPublish(fingerprint, response));
        return new PublishAcceptance(response, publishing, true);
    }

    public synchronized PublishResponse replayPublish(String key, PublishFingerprint fingerprint) {
        AcceptedPublish prior = acceptedPublishes.get(key);
        if (prior == null) return null;
        if (!prior.fingerprint().equals(fingerprint))
            throw new com.meetingautomation.api.idempotency.IdempotencyKeyConflictException();
        return prior.response();
    }

    public synchronized void rollbackPublish(String sessionId, String key) {
        AcceptedPublish accepted = acceptedPublishes.get(key);
        if (accepted != null && accepted.fingerprint().sessionId().equals(sessionId)) acceptedPublishes.remove(key);
        MeetingSession current = sessions.get(sessionId);
        if (current != null && "PUBLISHING".equals(current.status())) {
            MeetingSession prior = publishPreAcceptance.remove(sessionId);
            sessions.put(sessionId, prior == null ? current : prior);
        }
        publishSnapshots.remove(sessionId);
    }

    public synchronized MeetingSession completePublish(String sessionId, com.meetingautomation.document.MeetingDocumentRef ref) {
        MeetingSession current = requirePublishing(sessionId);
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "DOCUMENT_SAVED",
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(), null);
        sessions.put(sessionId, updated);
        publishPreAcceptance.remove(sessionId);
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        publishSnapshots.put(sessionId, new PublishSnapshot(prior.recipients(), prior.notificationEnabled(), ref, null));
        return updated;
    }

    public synchronized MeetingSession failPublish(String sessionId) {
        MeetingSession current = requirePublishing(sessionId);
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "DOCUMENT_FAILED",
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(), null);
        sessions.put(sessionId, updated);
        publishPreAcceptance.remove(sessionId);
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        publishSnapshots.put(sessionId, new PublishSnapshot(prior.recipients(), prior.notificationEnabled(), null,
                "DOCUMENT_FAILURE"));
        return updated;
    }

    public PublishSnapshot findPublishSnapshot(String sessionId) { return publishSnapshots.get(sessionId); }

    private MeetingSession requirePublishing(String sessionId) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null || !"PUBLISHING".equals(current.status())) throw new SessionStateConflictException();
        return current;
    }

    public record PublishFingerprint(String sessionId, int expectedVersion, java.util.List<String> recipients,
            boolean notificationEnabled) { public PublishFingerprint { recipients = java.util.List.copyOf(recipients); } }
    public record PublishAcceptance(PublishResponse response, MeetingSession session, boolean newlyAccepted) { }
    public record PublishSnapshot(java.util.List<String> recipients, boolean notificationEnabled,
            com.meetingautomation.document.MeetingDocumentRef document, String errorCode) {
        public PublishSnapshot { recipients = java.util.List.copyOf(recipients); }
    }
    private record AcceptedPublish(PublishFingerprint fingerprint, PublishResponse response) { }

    public Optional<MeetingSessionStatusResponse.LastOperation> findLastOperation(String sessionId) {
        return Optional.ofNullable(lastOperations.get(sessionId));
    }

    public synchronized boolean isActiveMinutesRegeneration(String sessionId, String operationId) {
        MeetingSession current = sessions.get(sessionId);
        MinutesRegenerationBackup backup = minutesRegenerationBackups.get(sessionId);
        return current != null && backup != null && backup.operationId().equals(operationId)
                && "PROCESSING".equals(current.status())
                && "DRAFT_REGENERATION".equals(current.processingStage());
    }

    /** Starts a regeneration while preserving its complete Review snapshot for a fenced rollback. */
    public synchronized MeetingSession startMinutesRegeneration(
            String sessionId, int expectedVersion, String operationId, String requestedTemplateId) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (!"REVIEW".equals(current.status())) throw new SessionStateConflictException();
        if (current.version() != expectedVersion) throw new SessionVersionConflictException();
        if (operationId == null || operationId.isBlank() || requestedTemplateId == null
                || requestedTemplateId.isBlank() || current.minutes() == null) {
            throw new IllegalArgumentException("Minutes regeneration cannot be started.");
        }
        MinutesRegenerationBackup backup = new MinutesRegenerationBackup(operationId, current.meeting(),
                current.minutes(), requestedTemplateId);
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "PROCESSING",
                "DRAFT_REGENERATION", 0, current.speakers(), current.transcript(), null);
        minutesRegenerationBackups.put(sessionId, backup);
        lastOperations.remove(sessionId);
        sessions.put(sessionId, updated);
        return updated;
    }

    /** Commits the complete generated Minutes and selected template as one fenced mutation. */
    public synchronized MeetingSession completeMinutesRegeneration(
            String sessionId, String operationId, String templateId, String templateVersion,
            StructuredMinutes replacementMinutes) {
        MeetingSession current = requireActiveMinutesRegeneration(sessionId, operationId);
        if (templateId == null || templateId.isBlank() || templateVersion == null || templateVersion.isBlank()
                || replacementMinutes == null || !templateId.equals(replacementMinutes.templateId())
                || !templateVersion.equals(replacementMinutes.templateVersion())) {
            throw new IllegalArgumentException("Minutes regeneration result is invalid.");
        }
        MeetingSession.Meeting updatedMeeting = new MeetingSession.Meeting(
                current.meeting().title(), templateId, templateVersion,
                current.meeting().participantIds(), current.meeting().timezone(),
                current.meeting().participantReferences());
        MeetingSession updated = new MeetingSession(current.sessionId(), Math.addExact(current.version(), 1),
                "REVIEW", current.documentConnectionVersion(), updatedMeeting, "DRAFT_READY", 100,
                current.speakers(), current.transcript(), null, replacementMinutes, current.createdAt());
        minutesRegenerationBackups.remove(sessionId);
        sessions.put(sessionId, updated);
        lastOperations.put(sessionId, new MeetingSessionStatusResponse.LastOperation(
                "MINUTES_REGENERATION", "SUCCEEDED", templateId, null));
        return updated;
    }

    /** Restores the exact pre-regeneration Review snapshot only while this operation still owns the session. */
    public synchronized MeetingSession failMinutesRegeneration(String sessionId, String operationId) {
        MeetingSession current = requireActiveMinutesRegeneration(sessionId, operationId);
        MinutesRegenerationBackup backup = minutesRegenerationBackups.get(sessionId);
        MeetingSession updated = new MeetingSession(current.sessionId(), Math.addExact(current.version(), 1),
                "REVIEW", current.documentConnectionVersion(), backup.meeting(), "DRAFT_READY", 100,
                current.speakers(), current.transcript(), null, backup.minutes(), current.createdAt());
        minutesRegenerationBackups.remove(sessionId);
        sessions.put(sessionId, updated);
        lastOperations.put(sessionId, new MeetingSessionStatusResponse.LastOperation(
                "MINUTES_REGENERATION", "FAILED", backup.requestedTemplateId(),
                "MINUTES_REGENERATION_FAILED"));
        return updated;
    }

    private MeetingSession requireActiveMinutesRegeneration(String sessionId, String operationId) {
        MeetingSession current = sessions.get(sessionId);
        MinutesRegenerationBackup backup = minutesRegenerationBackups.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (backup == null || !backup.operationId().equals(operationId)
                || !"PROCESSING".equals(current.status())
                || !"DRAFT_REGENERATION".equals(current.processingStage())) {
            throw new SessionStateConflictException();
        }
        return current;
    }

    private record MinutesRegenerationBackup(
            String operationId, MeetingSession.Meeting meeting,
            StructuredMinutes minutes, String requestedTemplateId) { }

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
                current.meeting(), processingStage, progressPercent, speakers, transcript, failure, minutes,
                current.createdAt());
    }
}

