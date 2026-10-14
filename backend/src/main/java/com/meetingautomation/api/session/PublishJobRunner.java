package com.meetingautomation.api.session;

import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.api.error.FailureContext;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantListingProvider;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.settings.DocumentProviderFactory;
import com.meetingautomation.document.transfer.DocumentTransferPort;
import com.meetingautomation.document.transfer.TransferMarker;
import com.meetingautomation.document.transfer.TransferRecord;
import com.meetingautomation.email.EmailProvider;
import com.meetingautomation.email.EmailSettings;
import com.meetingautomation.email.MeetingEmailCommand;
import com.meetingautomation.email.RecipientDeliveryResult;
import com.meetingautomation.notification.DeliveryResult;
import com.meetingautomation.notification.MeetingPublishedCommand;
import com.meetingautomation.notification.NotificationProvider;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class PublishJobRunner {
    private static final Logger LOG = LoggerFactory.getLogger(PublishJobRunner.class);
    private final MeetingSessionStore sessions;
    private final DocumentProviderFactory providers;
    private final EmailProvider emailProvider;
    private final NotificationProvider notificationProvider;
    PublishJobRunner(MeetingSessionStore sessions, DocumentProviderFactory providers, EmailProvider emailProvider,
            NotificationProvider notificationProvider) {
        this.sessions = sessions; this.providers = providers; this.emailProvider = emailProvider;
        this.notificationProvider = notificationProvider;
    }

    void run(PublishJob job) {
        String sessionId = job.session().sessionId();
        String traceId = FailureContext.correlationId(job.traceId());
        try {
            DocumentStructureProvider provider = providers.create(job.connection());
            if (!(provider instanceof DocumentTransferPort transfer)) throw new IllegalStateException("Publish port unavailable.");
            MeetingDocumentRef ref = transfer.publishMeeting(toRecord(job.session()));
            sessions.withSessionLock(job.session().sessionId(), () -> {
                if (sessions.find(job.session().sessionId()).filter(s -> "PUBLISHING".equals(s.status())).isPresent())
                    sessions.completePublish(job.session().sessionId(), ref);
                return null;
            });
            sendSelectedEmail(job, provider, ref, traceId);
            sendSelectedNotification(job, ref, traceId);
            LOG.atInfo().addKeyValue("sessionId", sessionId).addKeyValue("traceId", traceId)
                    .addKeyValue("stage", "DOCUMENT_SAVE").log("Meeting publish completed.");
        } catch (RuntimeException failure) {
            try {
                sessions.withSessionLock(sessionId, () -> {
                    if (sessions.find(sessionId).filter(s -> "PUBLISHING".equals(s.status())).isPresent())
                        sessions.failPublish(sessionId);
                    return null;
                });
            } catch (RuntimeException ignored) { }
            FailureContext.from(sessionId, "DOCUMENT_SAVE", traceId, "DOCUMENT_FAILED",
                    failure instanceof DocumentProviderException providerFailure && providerFailure.retryable(),
                    java.time.Instant.now()).ifPresent(context -> context.logWarning(LOG));
        } finally {
            job.lease().close();
        }
    }

    private void sendSelectedNotification(PublishJob job, MeetingDocumentRef ref, String traceId) {
        String sessionId = job.session().sessionId();
        MeetingSessionStore.PublishSnapshot snapshot = sessions.findPublishSnapshot(sessionId);
        if (snapshot == null || !snapshot.notificationEnabled() || snapshot.notificationDelivery() == null) return;
        NotificationDelivery sending = sessions.withSessionLock(sessionId,
                () -> sessions.beginNotificationDelivery(sessionId));
        if (sending == null) return;

        NotificationDelivery outcome;
        try {
            var meeting = job.session().meeting();
            var meetingAt = job.session().createdAt().atZone(ZoneId.of(meeting.timezone())).toOffsetDateTime();
            DeliveryResult result = notificationProvider.sendMeetingPublished(
                    new MeetingPublishedCommand(meeting.title(), meetingAt, ref.documentUrl()));
            outcome = result != null && result.status() == DeliveryResult.Status.SENT
                    ? sending.sent()
                    : sending.failed(result != null && result.retryable(), "NOTIFICATION_FAILED");
        } catch (RuntimeException failure) {
            outcome = sending.failed(false, "NOTIFICATION_FAILED");
        }
        NotificationDelivery completed = outcome;
        if ("FAILED".equals(outcome.status())) {
            FailureContext.from(sessionId, "NOTIFICATION", traceId, "NOTIFICATION_FAILED", outcome.retryable(),
                    java.time.Instant.now()).ifPresent(context -> context.logWarning(LOG));
        }
        sessions.withSessionLock(sessionId, () -> {
            sessions.completeNotificationDelivery(sessionId, completed);
            return null;
        });
    }

    private void sendSelectedEmail(PublishJob job, DocumentStructureProvider provider, MeetingDocumentRef ref,
            String traceId) {
        String sessionId = job.session().sessionId();
        MeetingSessionStore.PublishSnapshot snapshot = sessions.findPublishSnapshot(sessionId);
        java.util.List<EmailDelivery> deliveries = snapshot == null ? java.util.List.of() : snapshot.deliveries();
        if (deliveries.isEmpty()) return;
        try {
            if (!(provider instanceof ParticipantListingProvider participantProvider)) {
                completeFailures(sessionId, deliveries, true);
                return;
            }
            var structure = participantProvider.discoverParticipantStructure(job.connection().rootId());
            java.util.List<Participant> participants = java.util.List.copyOf(
                    participantProvider.listParticipants(structure.participantsPageId()));
            if (participants.stream().anyMatch(p -> p == null || p.id() == null || p.id().isBlank())
                    || participants.stream().map(Participant::id).distinct().count() != participants.size()) {
                completeFailures(sessionId, deliveries, true);
                return;
            }
            Map<String, Participant> byId = participants.stream().collect(Collectors.toMap(
                    Participant::id, Function.identity()));
            Map<String, EmailDelivery> deliveryById = new java.util.HashMap<>(deliveries.stream().collect(
                    Collectors.toMap(EmailDelivery::recipientParticipantId, Function.identity())));
            Map<String, Participant> selected = deliveryById.keySet().stream()
                    .map(byId::get).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toMap(Participant::id, Function.identity()));
            java.util.List<MeetingEmailCommand.Recipient> validRecipients = new ArrayList<>();
            java.util.Map<String, EmailDelivery> outcomes = new java.util.HashMap<>();
            for (EmailDelivery delivery : deliveries) {
                Participant participant = selected.get(delivery.recipientParticipantId());
                if (participant == null || !EmailSettings.validAddress(participant.email())) {
                    outcomes.put(delivery.recipientParticipantId(), delivery.failed(true, "EMAIL_FAILED"));
                } else {
                    validRecipients.add(new MeetingEmailCommand.Recipient(participant.id(), participant.email().trim()));
                }
            }
            if (!validRecipients.isEmpty()) {
                java.util.Set<String> recipientIds = validRecipients.stream()
                        .map(MeetingEmailCommand.Recipient::participantId).collect(Collectors.toSet());
                java.util.List<EmailDelivery> sending = sessions.withSessionLock(sessionId,
                        () -> sessions.beginEmailDelivery(sessionId, recipientIds));
                Map<String, EmailDelivery> sendingById = sending.stream().collect(Collectors.toMap(
                        EmailDelivery::recipientParticipantId, Function.identity()));
                deliveryById.putAll(sendingById);
                java.util.List<RecipientDeliveryResult> results;
                try {
                    String summary = job.session().minutes() == null ? "" : job.session().minutes().summary();
                    String textBody = summary.isBlank() ? ref.documentUrl()
                            : summary + "\n\n" + ref.documentUrl();
                    results = emailProvider.sendMeetingEmail(new MeetingEmailCommand(
                            "Meeting minutes: " + job.session().meeting().title(),
                            textBody, ref.documentUrl(), validRecipients));
                } catch (RuntimeException providerFailure) {
                    for (MeetingEmailCommand.Recipient recipient : validRecipients) {
                        outcomes.put(recipient.participantId(), deliveryById.get(recipient.participantId())
                                .failed(false, "EMAIL_FAILED"));
                    }
                    results = java.util.List.of();
                }
                if (results != null) {
                    Map<String, RecipientDeliveryResult> resultById = results.stream()
                            .filter(java.util.Objects::nonNull)
                            .filter(result -> result.recipientParticipantId() != null)
                            .collect(Collectors.toMap(RecipientDeliveryResult::recipientParticipantId,
                                    Function.identity(), (first, ignored) -> first));
                    for (MeetingEmailCommand.Recipient recipient : validRecipients) {
                        if (outcomes.containsKey(recipient.participantId())) continue;
                        EmailDelivery delivery = deliveryById.get(recipient.participantId());
                        RecipientDeliveryResult result = resultById.get(recipient.participantId());
                        if (result == null || result.status() == null) {
                            outcomes.put(recipient.participantId(), delivery.failed(false, "EMAIL_FAILED"));
                        } else if (result.status() == RecipientDeliveryResult.Status.FAILED) {
                            outcomes.put(recipient.participantId(), delivery.failed(result.retryable(), "EMAIL_FAILED"));
                        } else {
                            outcomes.put(recipient.participantId(), delivery.sent());
                        }
                    }
                }
            }
            java.util.List<EmailDelivery> completed = deliveries.stream()
                    .map(delivery -> outcomes.getOrDefault(delivery.recipientParticipantId(),
                            delivery.failed(false, "EMAIL_FAILED")))
                    .toList();
            completed.stream().filter(delivery -> "FAILED".equals(delivery.status())).forEach(delivery ->
                    FailureContext.from(sessionId, "EMAIL_DELIVERY", traceId, "EMAIL_FAILED", delivery.retryable(),
                            java.time.Instant.now()).ifPresent(context -> context.logWarning(LOG)));
            sessions.withSessionLock(sessionId, () -> {
                sessions.completeEmailDelivery(sessionId, completed);
                return null;
            });
        } catch (DocumentProviderException failure) {
            completeFailures(sessionId, deliveries, failure.retryable());
        } catch (RuntimeException failure) {
            completeFailures(sessionId, deliveries, true);
        }
    }

    private void completeFailures(String sessionId, java.util.List<EmailDelivery> deliveries, boolean retryable) {
        java.util.List<EmailDelivery> failed = deliveries.stream()
                .map(delivery -> delivery.failed(retryable, "EMAIL_FAILED")).toList();
        sessions.withSessionLock(sessionId, () -> {
            sessions.completeEmailDelivery(sessionId, failed);
            return null;
        });
    }

    private static TransferRecord toRecord(MeetingSession session) {
        var meeting = session.meeting();
        var meetingAt = session.createdAt().atZone(ZoneId.of(meeting.timezone())).toOffsetDateTime();
        List<ParticipantSummary> participants = meeting.participantReferences().stream()
                .map(p -> new ParticipantSummary(p.id(), p.name())).toList();
        List<TransferMarker.Speaker> speakers = session.speakers().stream()
                .map(s -> new TransferMarker.Speaker(s.speakerId(), s.label(), s.participantId())).toList();
        TransferMarker marker = new TransferMarker("meeting-automation", "meeting", "1.0", session.sessionId(),
                meetingAt, meeting.templateId(), meeting.participantIds(), speakers, null, null, null);
        return new TransferRecord(null, null, null, marker, meeting.title(), session.minutes(), session.transcript(),
                participants, null);
    }
}
