package com.meetingautomation.api.session;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.ParticipantListingProvider;
import com.meetingautomation.document.Participant;
import com.meetingautomation.email.EmailProvider;
import com.meetingautomation.email.EmailSettings;
import com.meetingautomation.email.MeetingEmailCommand;
import com.meetingautomation.email.RecipientDeliveryResult;
import com.meetingautomation.notification.DeliveryResult;
import com.meetingautomation.notification.MeetingPublishedCommand;
import com.meetingautomation.notification.NotificationProvider;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.settings.DocumentProviderFactory;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class DeliveryRetryJobRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRetryJobRunner.class);
    private final MeetingSessionStore sessions;
    private final DocumentProviderResolver resolver;
    private final DocumentProviderFactory providerFactory;
    private final EmailProvider emailProvider;
    private final NotificationProvider notificationProvider;

    DeliveryRetryJobRunner(MeetingSessionStore sessions, DocumentProviderResolver resolver,
            DocumentProviderFactory providerFactory, EmailProvider emailProvider,
            NotificationProvider notificationProvider) {
        this.sessions = sessions;
        this.resolver = resolver;
        this.providerFactory = providerFactory;
        this.emailProvider = emailProvider;
        this.notificationProvider = notificationProvider;
    }

    void run(String sessionId, String deliveryId) {
        try {
            MeetingSession session = sessions.find(sessionId).orElse(null);
            MeetingSessionStore.PublishSnapshot snapshot = sessions.findPublishSnapshot(sessionId);
            if (session == null || snapshot == null || snapshot.document() == null) return;
            if (snapshot.deliveries().stream().anyMatch(delivery -> delivery.deliveryId().equals(deliveryId)))
                retryEmail(session, snapshot, deliveryId);
            else if (snapshot.notificationDelivery() != null
                    && snapshot.notificationDelivery().deliveryId().equals(deliveryId))
                retryNotification(session, snapshot, deliveryId);
        } catch (RuntimeException failure) {
            LOG.warn("Delivery retry sessionId={} outcome=FAILED errorCode=DELIVERY_RETRY_FAILURE", sessionId);
        }
    }

    private void retryEmail(MeetingSession session, MeetingSessionStore.PublishSnapshot snapshot, String deliveryId) {
        EmailDelivery delivery = snapshot.deliveries().stream()
                .filter(item -> item.deliveryId().equals(deliveryId)).findFirst().orElse(null);
        if (delivery == null) return;
        Participant recipient;
        try {
            var connection = resolver.active().orElse(null);
            if (connection == null) {
                finish(session.sessionId(), deliveryId, false, true);
                return;
            }
            DocumentStructureProvider provider = providerFactory.create(connection);
            if (!(provider instanceof ParticipantListingProvider participantsProvider)) {
                finish(session.sessionId(), deliveryId, false, true);
                return;
            }
            var structure = participantsProvider.discoverParticipantStructure(connection.rootId());
            List<Participant> participants = List.copyOf(participantsProvider.listParticipants(structure.participantsPageId()));
            Map<String, Participant> byId = participants.stream().filter(java.util.Objects::nonNull)
                    .filter(participant -> participant.id() != null)
                    .collect(Collectors.toMap(Participant::id, Function.identity(), (first, ignored) -> first));
            recipient = byId.get(delivery.recipientParticipantId());
            if (recipient == null || !EmailSettings.validAddress(recipient.email())) {
                finish(session.sessionId(), deliveryId, false, true);
                return;
            }
        } catch (DocumentProviderException failure) {
            finish(session.sessionId(), deliveryId, false, failure.retryable());
            return;
        } catch (RuntimeException preflightFailure) {
            finish(session.sessionId(), deliveryId, false, true);
            return;
        }
        if (!sessions.withSessionLock(session.sessionId(), () -> sessions.beginDeliveryRetry(session.sessionId(), deliveryId)))
            return;
        String summary = session.minutes() == null ? "" : session.minutes().summary();
        String documentUrl = snapshot.document().documentUrl();
        String textBody = summary.isBlank() ? documentUrl : summary + "\n\n" + documentUrl;
        try {
            List<RecipientDeliveryResult> results = emailProvider.sendMeetingEmail(new MeetingEmailCommand(
                    "Meeting minutes: " + session.meeting().title(), textBody, documentUrl,
                    List.of(new MeetingEmailCommand.Recipient(recipient.id(), recipient.email().trim()))));
            RecipientDeliveryResult result = results == null ? null : results.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(item -> delivery.recipientParticipantId().equals(item.recipientParticipantId()))
                    .findFirst().orElse(null);
            finish(session.sessionId(), deliveryId,
                    result != null && result.status() == RecipientDeliveryResult.Status.SENT,
                    result != null && result.retryable());
        } catch (RuntimeException providerFailure) {
            finish(session.sessionId(), deliveryId, false, false);
        }
    }

    private void retryNotification(MeetingSession session, MeetingSessionStore.PublishSnapshot snapshot,
            String deliveryId) {
        if (!sessions.withSessionLock(session.sessionId(), () -> sessions.beginDeliveryRetry(session.sessionId(), deliveryId)))
            return;
        try {
            var meeting = session.meeting();
            var meetingAt = session.createdAt().atZone(ZoneId.of(meeting.timezone())).toOffsetDateTime();
            DeliveryResult result = notificationProvider.sendMeetingPublished(
                    new MeetingPublishedCommand(meeting.title(), meetingAt, snapshot.document().documentUrl()));
            finish(session.sessionId(), deliveryId, result != null && result.status() == DeliveryResult.Status.SENT,
                    result != null && result.retryable());
        } catch (RuntimeException failure) {
            finish(session.sessionId(), deliveryId, false, false);
        }
    }

    private void finish(String sessionId, String deliveryId, boolean sent, boolean retryable) {
        sessions.withSessionLock(sessionId, () -> {
            sessions.completeDeliveryRetry(sessionId, deliveryId, sent, retryable,
                    deliveryId.startsWith("dlv_") ? errorCode(sessionId, deliveryId) : "DELIVERY_FAILED");
            return null;
        });
    }

    private String errorCode(String sessionId, String deliveryId) {
        MeetingSessionStore.PublishSnapshot snapshot = sessions.findPublishSnapshot(sessionId);
        if (snapshot != null && snapshot.deliveries().stream().anyMatch(item -> item.deliveryId().equals(deliveryId)))
            return "EMAIL_FAILED";
        return "NOTIFICATION_FAILED";
    }
}
