package com.meetingautomation.api.session;

import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.api.error.FailureContext;
import com.meetingautomation.api.error.FailureContextReporter;
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
import com.meetingautomation.api.template.TemplateCatalog;
import com.meetingautomation.minutes.MinutesDocumentCommand;
import com.meetingautomation.minutes.MinutesDocumentWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
    private final FailureContextReporter failureContextReporter;
    private final TemplateCatalog templates;
    private final MinutesDocumentWriter minutesDocumentWriter;
    PublishJobRunner(MeetingSessionStore sessions, DocumentProviderFactory providers, EmailProvider emailProvider,
            NotificationProvider notificationProvider, FailureContextReporter failureContextReporter,
            TemplateCatalog templates, MinutesDocumentWriter minutesDocumentWriter) {
        this.sessions = sessions; this.providers = providers; this.emailProvider = emailProvider;
        this.notificationProvider = notificationProvider;
        this.failureContextReporter = failureContextReporter;
        this.templates = templates;
        this.minutesDocumentWriter = minutesDocumentWriter;
    }

    void run(PublishJob job) {
        String sessionId = job.session().sessionId();
        String traceId = FailureContext.correlationId(job.traceId());
        try {
            DocumentStructureProvider provider = providers.create(job.connection());
            if (!(provider instanceof DocumentTransferPort transfer)) throw new IllegalStateException("Publish port unavailable.");
            var template = templates.loadTemplate(job.session().meeting().templateId());
            OffsetDateTime meetingAt = meetingAtAtSave(job.session());
            String renderedMarkdown = minutesDocumentWriter.render(new MinutesDocumentCommand(
                    template.content(), reviewedMinutes(job.session(), meetingAt)));
            MeetingDocumentRef ref = transfer.publishMeeting(toRecord(job.session(), meetingAt, renderedMarkdown));
            sessions.withSessionLock(job.session().sessionId(), () -> {
                if (sessions.find(job.session().sessionId()).filter(s -> "PUBLISHING".equals(s.status())).isPresent())
                    sessions.completePublish(job.session().sessionId(), ref);
                return null;
            });
            sendSelectedEmail(job, provider, ref, renderedMarkdown, traceId);
            sendSelectedNotification(job, ref, traceId);
            LOG.atInfo().addKeyValue("sessionId", sessionId).addKeyValue("traceId", traceId)
                    .addKeyValue("stage", "DOCUMENT_SAVE").log("Meeting publish completed.");
        } catch (RuntimeException failure) {
            try {
                sessions.withSessionLock(sessionId, () -> {
                    if (sessions.find(sessionId).filter(s -> "PUBLISHING".equals(s.status())).isPresent())
                        sessions.failPublish(sessionId,
                                failure instanceof DocumentProviderException providerFailure
                                        ? providerFailure.code() : "DOCUMENT_FAILED",
                                !(failure instanceof DocumentProviderException providerFailure)
                                        || providerFailure.retryable());
                    return null;
                });
            } catch (RuntimeException ignored) { }
            FailureContext.from(sessionId, "DOCUMENT_SAVE", traceId, "DOCUMENT_FAILED",
                    failure instanceof DocumentProviderException providerFailure && providerFailure.retryable(),
                    java.time.Instant.now()).ifPresent(context -> failureContextReporter.report(context, LOG));
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
            var meetingAt = job.session().meetingAt();
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
                    java.time.Instant.now()).ifPresent(context -> failureContextReporter.report(context, LOG));
        }
        sessions.withSessionLock(sessionId, () -> {
            sessions.completeNotificationDelivery(sessionId, completed);
            return null;
        });
    }

    private void sendSelectedEmail(PublishJob job, DocumentStructureProvider provider, MeetingDocumentRef ref,
            String renderedMinutes, String traceId) {
        String sessionId = job.session().sessionId();
        MeetingSessionStore.PublishSnapshot snapshot = sessions.findPublishSnapshot(sessionId);
        java.util.List<EmailDelivery> deliveries = snapshot == null ? java.util.List.of() : snapshot.deliveries();
        if (deliveries.isEmpty()) return;
        try {
            if (!(provider instanceof ParticipantListingProvider participantProvider)) {
                completeFailures(sessionId, deliveries, true, traceId);
                return;
            }
            var structure = participantProvider.discoverParticipantStructure(job.connection().rootId());
            java.util.List<Participant> participants = java.util.List.copyOf(
                    participantProvider.listParticipants(structure.participantsPageId()));
            if (participants.stream().anyMatch(p -> p == null || p.id() == null || p.id().isBlank())
                    || participants.stream().map(Participant::id).distinct().count() != participants.size()) {
                completeFailures(sessionId, deliveries, true, traceId);
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
                    results = emailProvider.sendMeetingEmail(new MeetingEmailCommand(
                            "회의록 공유: " + job.session().meeting().title(),
                            renderedMinutes, ref.documentUrl(), validRecipients));
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
            logEmailFailures(sessionId, traceId, completed);
            sessions.withSessionLock(sessionId, () -> {
                sessions.completeEmailDelivery(sessionId, completed);
                return null;
            });
        } catch (DocumentProviderException failure) {
            completeFailures(sessionId, deliveries, failure.retryable(), traceId);
        } catch (RuntimeException failure) {
            completeFailures(sessionId, deliveries, true, traceId);
        }
    }

    private void completeFailures(String sessionId, java.util.List<EmailDelivery> deliveries, boolean retryable,
            String traceId) {
        java.util.List<EmailDelivery> failed = deliveries.stream()
                .map(delivery -> delivery.failed(retryable, "EMAIL_FAILED")).toList();
        logEmailFailures(sessionId, traceId, failed);
        sessions.withSessionLock(sessionId, () -> {
            sessions.completeEmailDelivery(sessionId, failed);
            return null;
        });
    }

    private void logEmailFailures(String sessionId, String traceId, java.util.List<EmailDelivery> deliveries) {
        deliveries.stream().filter(delivery -> "FAILED".equals(delivery.status())).forEach(delivery ->
                FailureContext.from(sessionId, "EMAIL_DELIVERY", traceId, "EMAIL_FAILED", delivery.retryable(),
                        java.time.Instant.now()).ifPresent(context -> failureContextReporter.report(context, LOG)));
    }

    private static TransferRecord toRecord(MeetingSession session, OffsetDateTime meetingAt, String renderedMarkdown) {
        var meeting = session.meeting();
        List<ParticipantSummary> participants = meeting.participantReferences().stream()
                .map(p -> new ParticipantSummary(p.id(), p.name())).toList();
        List<TransferMarker.Speaker> speakers = session.speakers().stream()
                .map(s -> new TransferMarker.Speaker(s.speakerId(), s.label(), s.participantId())).toList();
        TransferMarker marker = new TransferMarker("meeting-automation", "meeting", "1.0", session.sessionId(),
                meetingAt, meeting.templateId(), meeting.participantIds(), speakers, null, null, null);
        return new TransferRecord(null, null, null, marker, meeting.title(), session.minutes(), session.transcript(),
                participants, null, renderedMarkdown, null);
    }

    private static OffsetDateTime meetingAtAtSave(MeetingSession session) {
        var meeting = session.meeting();
        if (meeting.meetingDate() == null) return session.meetingAt();
        ZoneId zone = ZoneId.of(meeting.timezone());
        ZonedDateTime savedAt = ZonedDateTime.now(zone);
        return meeting.meetingDate().atTime(savedAt.toLocalTime().withNano(0)).atZone(zone).toOffsetDateTime();
    }

    private static String reviewedMinutes(MeetingSession session, OffsetDateTime meetingAt) {
        var minutes = session.minutes();
        var meeting = session.meeting();
        Map<String, String> participantNames = meeting.participantReferences().stream()
                .collect(Collectors.toMap(MeetingSession.ParticipantReference::id,
                        MeetingSession.ParticipantReference::name));
        StringBuilder content = new StringBuilder();
        content.append("## 확인된 회의 정보\n")
                .append("- 주제: ").append(meeting.title()).append('\n')
                .append("- 날짜 및 시간: ").append(meetingAt.format(DateTimeFormatter.ofPattern(
                        "yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT))).append(" (").append(meeting.timezone()).append(")\n")
                .append("- 참석자: ").append(participantNames.isEmpty() ? "미기록"
                        : String.join(", ", meeting.participantReferences().stream()
                                .map(MeetingSession.ParticipantReference::name).toList())).append('\n');
        List<String> speakerMappings = session.speakers().stream()
                .filter(speaker -> speaker.participantId() != null && participantNames.containsKey(speaker.participantId()))
                .map(speaker -> speaker.label() + ": " + participantNames.get(speaker.participantId()))
                .toList();
        if (!speakerMappings.isEmpty()) {
            content.append("- 화자 매핑: ").append(String.join(", ", speakerMappings)).append('\n');
        }
        content.append('\n');
        appendSection(content, "회의 요약", minutes.summary());
        appendList(content, "논의 사항", minutes.discussionPoints());
        appendList(content, "결정 사항", minutes.decisions());
        content.append("\n## 실행 항목\n");
        for (var item : minutes.actionItems()) {
            String owner = item.ownerParticipantId() == null ? "미정"
                    : participantNames.getOrDefault(item.ownerParticipantId(), "미정");
            content.append("- 할 일: ").append(item.task()).append(" | 담당자: ").append(owner)
                    .append(" | 기한: ").append(item.dueDate() == null ? "미정" : item.dueDate()).append('\n');
        }
        appendList(content, "후속 확인", minutes.followUps());
        return content.toString().strip();
    }

    private static void appendSection(StringBuilder target, String title, String value) {
        target.append("## ").append(title).append("\n").append(value == null ? "" : value.strip()).append('\n');
    }

    private static void appendList(StringBuilder target, String title, List<String> values) {
        target.append("\n## ").append(title).append('\n');
        values.forEach(value -> target.append("- ").append(value).append('\n'));
    }
}
