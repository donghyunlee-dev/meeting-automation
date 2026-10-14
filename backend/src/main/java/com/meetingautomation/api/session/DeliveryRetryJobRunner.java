package com.meetingautomation.api.session;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.api.error.FailureContext;
import com.meetingautomation.api.error.FailureContextReporter;
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
    private final FailureContextReporter failureContextReporter;

    DeliveryRetryJobRunner(MeetingSessionStore sessions, DocumentProviderResolver resolver,
            DocumentProviderFactory providerFactory, EmailProvider emailProvider,
            NotificationProvider notificationProvider, FailureContextReporter failureContextReporter) {
        this.sessions = sessions;
        this.resolver = resolver;
        this.providerFactory = providerFactory;
        this.emailProvider = emailProvider;
        this.notificationProvider = notificationProvider;
        this.failureContextReporter = failureContextReporter;
    }

    void run(String sessionId, String deliveryId) {
        run(sessionId, deliveryId, null);
    }

    void run(String sessionId, String deliveryId, String requestTraceId) {
        String traceId = FailureContext.correlationId(requestTraceId);
        try {
            MeetingSession session = sessions.find(sessionId).orElse(null);
            MeetingSessionStore.PublishSnapshot snapshot = sessions.findPublishSnapshot(sessionId);
            if (session == null || snapshot == null || snapshot.document() == null) return;
            if (snapshot.deliveries().stream().anyMatch(delivery -> delivery.deliveryId().equals(deliveryId)))
                retryEmail(session, snapshot, deliveryId, traceId);
            else if (snapshot.notificationDelivery() != null
                    && snapshot.notificationDelivery().deliveryId().equals(deliveryId))
                retryNotification(session, snapshot, deliveryId, traceId);
        } catch (RuntimeException failure) {
            String code = errorCode(sessionId, deliveryId);
            String stage = "EMAIL_FAILED".equals(code) ? "EMAIL_DELIVERY" : "NOTIFICATION";
            FailureContext.from(sessionId, stage, traceId, code, false, java.time.Instant.now())
                    .ifPresent(context -> failureContextReporter.report(context, LOG));
        }
    }

    private void retryEmail(MeetingSession session, MeetingSessionStore.PublishSnapshot snapshot, String deliveryId,
            String traceId) {
        EmailDelivery delivery = snapshot.deliveries().stream()
                .filter(item -> item.deliveryId().equals(deliveryId)).findFirst().orElse(null);
        if (delivery == null) return;
        Participant recipient;
        try {
            var connection = resolver.active().orElse(null);
            if (connection == null) {
                finish(session.sessionId(), deliveryId, false, true, traceId);
                return;
            }
            DocumentStructureProvider provider = providerFactory.create(connection);
            if (!(provider instanceof ParticipantListingProvider participantsProvider)) {
                finish(session.sessionId(), deliveryId, false, true, traceId);
                return;
            }
            var structure = participantsProvider.discoverParticipantStructure(connection.rootId());
            List<Participant> participants = List.copyOf(participantsProvider.listParticipants(structure.participantsPageId()));
            Map<String, Participant> byId = participants.stream().filter(java.util.Objects::nonNull)
                    .filter(participant -> participant.id() != null)
                    .collect(Collectors.toMap(Participant::id, Function.identity(), (first, ignored) -> first));
            recipient = byId.get(delivery.recipientParticipantId());
            if (recipient == null || !EmailSettings.validAddress(recipient.email())) {
                finish(session.sessionId(), deliveryId, false, true, traceId);
                return;
            }
        } catch (DocumentProviderException failure) {
            finish(session.sessionId(), deliveryId, false, failure.retryable(), traceId);
            return;
        } catch (RuntimeException preflightFailure) {
            finish(session.sessionId(), deliveryId, false, true, traceId);
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
                    result != null && result.retryable(), traceId);
        } catch (RuntimeException providerFailure) {
            finish(session.sessionId(), deliveryId, false, false, traceId);
        }
    }

    private void retryNotification(MeetingSession session, MeetingSessionStore.PublishSnapshot snapshot,
            String deliveryId, String traceId) {
        if (!sessions.withSessionLock(session.sessionId(), () -> sessions.beginDeliveryRetry(session.sessionId(), deliveryId)))
            return;
        try {
            var meeting = session.meeting();
            var meetingAt = session.createdAt().atZone(ZoneId.of(meeting.timezone())).toOffsetDateTime();
            DeliveryResult result = notificationProvider.sendMeetingPublished(
                    new MeetingPublishedCommand(meeting.title(), meetingAt, snapshot.document().documentUrl()));
            finish(session.sessionId(), deliveryId, result != null && result.status() == DeliveryResult.Status.SENT,
                    result != null && result.retryable(), traceId);
        } catch (RuntimeException failure) {
            finish(session.sessionId(), deliveryId, false, false, traceId);
        }
    }

    private void finish(String sessionId, String deliveryId, boolean sent, boolean retryable, String traceId) {
        String code = errorCode(sessionId, deliveryId);
        if (!sent) {
            String stage = "EMAIL_FAILED".equals(code) ? "EMAIL_DELIVERY" : "NOTIFICATION";
            FailureContext.from(sessionId, stage, traceId, code, retryable, java.time.Instant.now())
                    .ifPresent(context -> failureContextReporter.report(context, LOG));
        }
        sessions.withSessionLock(sessionId, () -> {
            sessions.completeDeliveryRetry(sessionId, deliveryId, sent, retryable,
                    deliveryId.startsWith("dlv_") ? code : "DELIVERY_FAILED");
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
