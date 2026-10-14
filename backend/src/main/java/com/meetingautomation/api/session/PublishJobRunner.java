package com.meetingautomation.api.session;

import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.settings.DocumentProviderFactory;
import com.meetingautomation.document.transfer.DocumentTransferPort;
import com.meetingautomation.document.transfer.TransferMarker;
import com.meetingautomation.document.transfer.TransferRecord;
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
    PublishJobRunner(MeetingSessionStore sessions, DocumentProviderFactory providers) {
        this.sessions = sessions; this.providers = providers;
    }

    void run(PublishJob job) {
        try {
            DocumentStructureProvider provider = providers.create(job.connection());
            if (!(provider instanceof DocumentTransferPort transfer)) throw new IllegalStateException("Publish port unavailable.");
            MeetingDocumentRef ref = transfer.publishMeeting(toRecord(job.session()));
            sessions.withSessionLock(job.session().sessionId(), () -> {
                if (sessions.find(job.session().sessionId()).filter(s -> "PUBLISHING".equals(s.status())).isPresent())
                    sessions.completePublish(job.session().sessionId(), ref);
                return null;
            });
            LOG.info("Document publish sessionId={} outcome=SUCCEEDED", job.session().sessionId());
        } catch (RuntimeException failure) {
            try {
                sessions.withSessionLock(job.session().sessionId(), () -> {
                    if (sessions.find(job.session().sessionId()).filter(s -> "PUBLISHING".equals(s.status())).isPresent())
                        sessions.failPublish(job.session().sessionId());
                    return null;
                });
            } catch (RuntimeException ignored) { }
            LOG.warn("Document publish sessionId={} outcome=FAILED errorCode=DOCUMENT_FAILURE", job.session().sessionId());
        } finally {
            job.lease().close();
        }
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
