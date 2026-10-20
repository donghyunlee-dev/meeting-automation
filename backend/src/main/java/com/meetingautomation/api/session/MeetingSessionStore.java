package com.meetingautomation.api.session;

import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.api.processing.ProcessingJob;
import com.meetingautomation.transcription.ProviderTranscriptionResponse;
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
    private final Map<String, ProcessingJob> processingJobs = new ConcurrentHashMap<>();
    private final Map<String, ProviderTranscriptionResponse> completedTranscriptions = new ConcurrentHashMap<>();
    private final Map<String, FailureFinalization> failureFinalizations = new ConcurrentHashMap<>();
    private final Map<String, PublishSnapshot> publishSnapshots = new ConcurrentHashMap<>();
    private final Map<String, PublishPreAcceptance> publishPreAcceptance = new ConcurrentHashMap<>();
    private final Map<DeliveryRetryKey, DeliveryRetryAcceptance> acceptedDeliveryRetries = new java.util.HashMap<>();
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
                acceptedDeliveryRetries.entrySet().removeIf(entry -> entry.getKey().sessionId().equals(sessionId));
                publishSnapshots.remove(sessionId);
                publishPreAcceptance.remove(sessionId);
                processingJobs.remove(sessionId);
                completedTranscriptions.remove(sessionId);
                failureFinalizations.remove(sessionId);
            }
            if (registration != null) registration.close();
            return removed != null;
        });
    }

    public Set<String> sessionIds() { return Set.copyOf(sessions.keySet()); }

    public void registerProcessingJob(ProcessingJob job) {
        if (job == null || job.sessionId() == null) throw new IllegalArgumentException("Processing job is invalid.");
        processingJobs.put(job.sessionId(), job);
    }

    public Optional<ProcessingJob> findProcessingJob(String sessionId) {
        return Optional.ofNullable(processingJobs.get(sessionId));
    }

    public void removeProcessingJob(String sessionId) {
        processingJobs.remove(sessionId);
    }

    public void saveTranscriptionResult(String sessionId, ProviderTranscriptionResponse response) {
        if (response == null) throw new IllegalArgumentException("Transcription result is required.");
        completedTranscriptions.put(sessionId, response);
    }

    public Optional<ProviderTranscriptionResponse> findTranscriptionResult(String sessionId) {
        return Optional.ofNullable(completedTranscriptions.get(sessionId));
    }

    public void removeTranscriptionResult(String sessionId) {
        completedTranscriptions.remove(sessionId);
    }

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
        publishPreAcceptance.put(sessionId, new PublishPreAcceptance(current, publishSnapshots.get(sessionId)));
        PublishSnapshot snapshot = new PublishSnapshot(java.util.List.copyOf(recipients), notificationEnabled, null, null, false,
                recipients.stream().map(EmailDelivery::pending).toList(),
                notificationEnabled ? NotificationDelivery.pending() : null);
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
            PublishPreAcceptance prior = publishPreAcceptance.remove(sessionId);
            sessions.put(sessionId, prior == null ? current : prior.session());
            if (prior != null && prior.publishSnapshot() != null)
                publishSnapshots.put(sessionId, prior.publishSnapshot());
            else publishSnapshots.remove(sessionId);
        }
    }

    public synchronized MeetingSession completePublish(String sessionId, com.meetingautomation.document.MeetingDocumentRef ref) {
        MeetingSession current = requirePublishing(sessionId);
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "DOCUMENT_SAVED",
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(), null);
        sessions.put(sessionId, updated);
        publishPreAcceptance.remove(sessionId);
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        publishSnapshots.put(sessionId, new PublishSnapshot(prior.recipients(), prior.notificationEnabled(), ref, null, false,
                prior.deliveries(), prior.notificationDelivery()));
        return updated;
    }

    public synchronized MeetingSession failPublish(String sessionId, String errorCode, boolean retryable) {
        MeetingSession current = requirePublishing(sessionId);
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "DOCUMENT_FAILED",
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(), null);
        sessions.put(sessionId, updated);
        publishPreAcceptance.remove(sessionId);
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        publishSnapshots.put(sessionId, new PublishSnapshot(prior.recipients(), prior.notificationEnabled(), null,
                errorCode, retryable, java.util.List.of(), null));
        return updated;
    }

    public synchronized java.util.List<EmailDelivery> beginEmailDelivery(
            String sessionId, java.util.Set<String> recipientIds) {
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        if (prior == null || prior.document() == null || recipientIds.isEmpty())
            return prior == null ? java.util.List.of() : prior.deliveries();
        java.time.Instant attemptedAt = java.time.Instant.now();
        java.util.List<EmailDelivery> deliveries = prior.deliveries().stream()
                .map(delivery -> recipientIds.contains(delivery.recipientParticipantId())
                        && "PENDING".equals(delivery.status()) ? delivery.sending(attemptedAt) : delivery)
                .toList();
        publishSnapshots.put(sessionId, new PublishSnapshot(prior.recipients(), prior.notificationEnabled(),
                prior.document(), prior.errorCode(), prior.retryable(), deliveries, prior.notificationDelivery()));
        advancePublishVersion(sessionId);
        return deliveries;
    }

    public synchronized void completeEmailDelivery(String sessionId, java.util.List<EmailDelivery> deliveries) {
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        if (prior == null || prior.document() == null || prior.deliveries().equals(deliveries)) return;
        publishSnapshots.put(sessionId, new PublishSnapshot(prior.recipients(), prior.notificationEnabled(),
                prior.document(), prior.errorCode(), prior.retryable(), deliveries, prior.notificationDelivery()));
        advancePublishVersion(sessionId);
    }

    public synchronized NotificationDelivery beginNotificationDelivery(String sessionId) {
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        if (prior == null || prior.document() == null || prior.notificationDelivery() == null
                || !"PENDING".equals(prior.notificationDelivery().status())) return null;
        NotificationDelivery sending = prior.notificationDelivery().sending(java.time.Instant.now());
        publishSnapshots.put(sessionId, new PublishSnapshot(prior.recipients(), prior.notificationEnabled(),
                prior.document(), prior.errorCode(), prior.retryable(), prior.deliveries(), sending));
        advancePublishVersion(sessionId);
        return sending;
    }

    public synchronized DeliveryRetryAcceptance acceptDeliveryRetry(String sessionId, String deliveryId, String key) {
        DeliveryRetryKey retryKey = new DeliveryRetryKey(sessionId, key);
        DeliveryRetryAcceptance accepted = acceptedDeliveryRetries.get(retryKey);
        if (accepted != null) {
            if (!accepted.deliveryId().equals(deliveryId))
                throw new com.meetingautomation.api.idempotency.IdempotencyKeyConflictException();
            return new DeliveryRetryAcceptance(accepted.deliveryId(), accepted.response(), accepted.before(),
                    accepted.beforeSession(), false);
        }
        if (!sessions.containsKey(sessionId)) throw new com.meetingautomation.api.audio.AudioSessionNotFoundException();
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        DeliveryView status = findDeliveryStatus(prior, deliveryId);
        if (status == null) throw new DeliveryRetryException(DeliveryRetryException.Reason.NOT_FOUND);
        if ("PENDING".equals(status.status()) || "SENDING".equals(status.status()))
            throw new DeliveryRetryException(DeliveryRetryException.Reason.IN_PROGRESS);
        if (!"FAILED".equals(status.status()) || !status.retryable())
            throw new DeliveryRetryException(DeliveryRetryException.Reason.NOT_RETRYABLE);
        PublishSnapshot pending = replaceDelivery(prior, deliveryId, retryPending(prior, deliveryId));
        publishSnapshots.put(sessionId, pending);
        advancePublishVersion(sessionId);
        DeliveryRetryAcceptance result = new DeliveryRetryAcceptance(deliveryId,
                new DeliveryRetryResponse(new DeliveryRetryResponse.Data(deliveryId, "PENDING")), prior,
                sessions.get(sessionId), true);
        acceptedDeliveryRetries.put(retryKey, result);
        return result;
    }

    public synchronized boolean beginDeliveryRetry(String sessionId, String deliveryId) {
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        if (prior == null || findDeliveryStatus(prior, deliveryId) == null) return false;
        DeliveryView status = findDeliveryStatus(prior, deliveryId);
        if (!"PENDING".equals(status.status())) return false;
        PublishSnapshot sending = replaceDelivery(prior, deliveryId, startAttempt(prior, deliveryId));
        publishSnapshots.put(sessionId, sending);
        advancePublishVersion(sessionId);
        return true;
    }

    public synchronized void completeDeliveryRetry(String sessionId, String deliveryId, boolean sent,
            boolean retryable, String errorCode) {
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        if (prior == null) return;
        DeliveryView current = findDeliveryStatus(prior, deliveryId);
        if (current == null || !("SENDING".equals(current.status()) || "PENDING".equals(current.status()))) return;
        PublishSnapshot completed = replaceDelivery(prior, deliveryId,
                finishAttempt(prior, deliveryId, sent, retryable, errorCode));
        if (completed.equals(prior)) return;
        publishSnapshots.put(sessionId, completed);
        advancePublishVersion(sessionId);
    }

    public synchronized void rollbackDeliveryRetry(String sessionId, String deliveryId, String key,
            DeliveryRetryAcceptance accepted) {
        DeliveryRetryKey retryKey = new DeliveryRetryKey(sessionId, key);
        if (acceptedDeliveryRetries.get(retryKey) != accepted) return;
        acceptedDeliveryRetries.remove(retryKey);
        PublishSnapshot current = publishSnapshots.get(sessionId);
        if (current == null) return;
        publishSnapshots.put(sessionId, accepted.before());
        MeetingSession session = sessions.get(sessionId);
        MeetingSession before = accepted.beforeSession();
        if (session != null && before != null) sessions.put(sessionId, before);
    }

    private static DeliveryView findDeliveryStatus(PublishSnapshot snapshot, String deliveryId) {
        if (snapshot == null || deliveryId == null) return null;
        for (EmailDelivery delivery : snapshot.deliveries()) if (delivery.deliveryId().equals(deliveryId))
            return new DeliveryView(delivery.status(), delivery.retryable());
        NotificationDelivery notification = snapshot.notificationDelivery();
        if (notification != null && notification.deliveryId().equals(deliveryId))
            return new DeliveryView(notification.status(), notification.retryable());
        return null;
    }

    private static DeliveryChange retryPending(PublishSnapshot snapshot, String deliveryId) {
        for (EmailDelivery delivery : snapshot.deliveries())
            if (delivery.deliveryId().equals(deliveryId)) return new DeliveryChange(delivery.retryPending(), null);
        NotificationDelivery notification = snapshot.notificationDelivery();
        return new DeliveryChange(null, notification.retryPending());
    }

    private static DeliveryChange startAttempt(PublishSnapshot snapshot, String deliveryId) {
        java.time.Instant attemptedAt = java.time.Instant.now();
        for (EmailDelivery delivery : snapshot.deliveries())
            if (delivery.deliveryId().equals(deliveryId)) return new DeliveryChange(delivery.sending(attemptedAt), null);
        return new DeliveryChange(null, snapshot.notificationDelivery().sending(attemptedAt));
    }

    private static DeliveryChange finishAttempt(PublishSnapshot snapshot, String deliveryId, boolean sent,
            boolean retryable, String errorCode) {
        for (EmailDelivery delivery : snapshot.deliveries()) if (delivery.deliveryId().equals(deliveryId))
            return new DeliveryChange(sent ? delivery.sent() : delivery.failed(retryable, errorCode), null);
        NotificationDelivery notification = snapshot.notificationDelivery();
        return new DeliveryChange(null, sent ? notification.sent() : notification.failed(retryable, errorCode));
    }

    private static PublishSnapshot replaceDelivery(PublishSnapshot snapshot, String deliveryId,
            DeliveryChange replacement) {
        java.util.List<EmailDelivery> deliveries = snapshot.deliveries().stream()
                .map(delivery -> delivery.deliveryId().equals(deliveryId) && replacement.email() != null
                        ? replacement.email() : delivery).toList();
        NotificationDelivery notification = replacement.notification() == null
                ? snapshot.notificationDelivery() : replacement.notification();
        return new PublishSnapshot(snapshot.recipients(), snapshot.notificationEnabled(), snapshot.document(),
                snapshot.errorCode(), snapshot.retryable(), deliveries, notification);
    }

    private record DeliveryRetryKey(String sessionId, String key) { }
    private record DeliveryChange(EmailDelivery email, NotificationDelivery notification) { }
    public record DeliveryRetryAcceptance(String deliveryId, DeliveryRetryResponse response, PublishSnapshot before,
            MeetingSession beforeSession, boolean newlyAccepted) { }
    private record DeliveryView(String status, boolean retryable) { }

    public synchronized void completeNotificationDelivery(String sessionId, NotificationDelivery delivery) {
        PublishSnapshot prior = publishSnapshots.get(sessionId);
        if (prior == null || prior.document() == null || prior.notificationDelivery() == null
                || prior.notificationDelivery().equals(delivery)) return;
        publishSnapshots.put(sessionId, new PublishSnapshot(prior.recipients(), prior.notificationEnabled(),
                prior.document(), prior.errorCode(), prior.retryable(), prior.deliveries(), delivery));
        advancePublishVersion(sessionId);
    }

    private void advancePublishVersion(String sessionId) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) return;
        sessions.put(sessionId, copy(current, Math.addExact(current.version(), 1), current.status(),
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(),
                current.processingFailure()));
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
            com.meetingautomation.document.MeetingDocumentRef document, String errorCode, boolean retryable,
            java.util.List<EmailDelivery> deliveries, NotificationDelivery notificationDelivery) {
        public PublishSnapshot {
            recipients = java.util.List.copyOf(recipients);
            deliveries = java.util.List.copyOf(deliveries);
        }
    }
    private record AcceptedPublish(PublishFingerprint fingerprint, PublishResponse response) { }
    private record PublishPreAcceptance(MeetingSession session, PublishSnapshot publishSnapshot) { }

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
                current.meeting().participantReferences(), current.meeting().meetingDate());
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

    public synchronized MeetingSession startProcessingRetry(String sessionId) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new com.meetingautomation.api.audio.AudioSessionNotFoundException();
        if (!"PROCESSING_FAILED".equals(current.status()) || current.processingFailure() == null) {
            throw new SessionStateConflictException();
        }
        MeetingSession retrying = copy(current, Math.addExact(current.version(), 1), "PROCESSING",
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(), null);
        sessions.put(sessionId, retrying);
        return retrying;
    }

    public synchronized MeetingSession rollbackProcessingRetry(
            String sessionId, int expectedVersion, MeetingSession previous) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (current.version() != expectedVersion || !"PROCESSING".equals(current.status())
                || current.processingFailure() != null) throw new SessionStateConflictException();
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
        return failProcessing(sessionId, stage, lastCompletedProgress, errorCode, retryable,
                false, false, null);
    }

    public synchronized MeetingSession failProcessing(String sessionId, String stage, int lastCompletedProgress,
            String errorCode, boolean retryable, boolean audioAvailable, boolean retryInputAvailable,
            java.time.Instant audioExpiresAt) {
        MeetingSession current = requireProcessing(sessionId);
        ProcessingFailure failure = new ProcessingFailure(stage, errorCode, retryable, audioAvailable,
                audioExpiresAt, false, retryInputAvailable);
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), "PROCESSING_FAILED",
                stage, lastCompletedProgress, current.speakers(), current.transcript(), failure);
        sessions.put(sessionId, updated);
        return updated;
    }

    public synchronized MeetingSession markAudioDownloadCompleted(String sessionId) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new com.meetingautomation.api.audio.AudioSessionNotFoundException();
        ProcessingFailure failure = current.processingFailure();
        if (!"PROCESSING_FAILED".equals(current.status()) || failure == null || !failure.audioAvailable()) {
            throw new SessionStateConflictException();
        }
        ProcessingFailure downloaded = new ProcessingFailure(failure.stage(), failure.errorCode(),
                failure.retryable(), failure.audioAvailable(), failure.audioExpiresAt(), true,
                failure.retryInputAvailable());
        MeetingSession updated = copy(current, Math.addExact(current.version(), 1), current.status(),
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(),
                downloaded);
        sessions.put(sessionId, updated);
        return updated;
    }

    public synchronized MeetingSession completeProcessingFailure(String sessionId, int expectedVersion,
            FailureFinalization finalization) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new com.meetingautomation.api.audio.AudioSessionNotFoundException();
        if (!"PROCESSING_FAILED".equals(current.status()) || current.version() != expectedVersion
                || current.processingFailure() == null || finalization == null) {
            if (current.version() != expectedVersion) throw new SessionVersionConflictException();
            throw new SessionStateConflictException();
        }
        ProcessingFailure old = current.processingFailure();
        ProcessingFailure finalized = new ProcessingFailure(old.stage(), old.errorCode(), old.retryable(),
                false, null, old.downloadCompleted(), false);
        MeetingSession completed = copy(current, Math.addExact(current.version(), 1), "COMPLETED_WITH_WARNINGS",
                current.processingStage(), current.progressPercent(), current.speakers(), current.transcript(), finalized);
        sessions.put(sessionId, completed);
        failureFinalizations.put(sessionId, finalization);
        return completed;
    }

    public Optional<FailureFinalization> findFailureFinalization(String sessionId) {
        return Optional.ofNullable(failureFinalizations.get(sessionId));
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

