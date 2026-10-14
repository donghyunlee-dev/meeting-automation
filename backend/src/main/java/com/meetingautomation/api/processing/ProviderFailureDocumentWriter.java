package com.meetingautomation.api.processing;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.document.CreateMeetingCommand;
import com.meetingautomation.document.DocumentProvider;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.MeetingMetadata;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.StructuredMinutes;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/** Creates an allowlisted failure-only meeting document and never invokes delivery providers. */
@Component
public final class ProviderFailureDocumentWriter implements FailureDocumentWriter {
    private final DocumentProviderResolver resolver;

    public ProviderFailureDocumentWriter(DocumentProviderResolver resolver) { this.resolver = resolver; }

    @Override
    public MeetingDocumentRef write(MeetingSession session, String audioDisposition) {
        try {
            DocumentProvider provider = resolver.selectedProvider()
                    .filter(DocumentProvider.class::isInstance).map(DocumentProvider.class::cast)
                    .orElseThrow(FailureDocumentWriteException::new);
            MeetingDocumentRef existing = provider.findMeetingBySessionId(session.sessionId()).orElse(null);
            if (existing != null) return existing;
            var meetingAt = session.createdAt().atZone(ZoneId.of(session.meeting().timezone())).toOffsetDateTime();
            List<ParticipantSummary> participants = session.meeting().participantReferences().stream()
                    .map(reference -> new ParticipantSummary(reference.id(), reference.name())).toList();
            var failure = session.processingFailure();
            String safeSummary = "Recording processing failed at " + failure.stage() + " ("
                    + failure.errorCode() + "). Audio disposition: " + audioDisposition + ".";
            StructuredMinutes summary = new StructuredMinutes(session.meeting().templateId(),
                    session.meeting().templateVersion(), safeSummary, List.of(), List.of(), List.of(), List.of());
            var metadata = new MeetingMetadata("1.0.0", session.sessionId(), meetingAt,
                    session.meeting().templateId(), session.meeting().participantIds());
            MeetingDocumentRef created = provider.createMeeting(new CreateMeetingCommand(session.meeting().title(),
                    meetingAt, participants, summary, List.of(), metadata));
            if (created == null || created.documentId() == null || created.documentId().isBlank()) {
                throw new FailureDocumentWriteException();
            }
            return created;
        } catch (FailureDocumentWriteException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException providerFailure) {
            throw new FailureDocumentWriteException();
        }
    }
}
