package com.meetingautomation.document.transfer;

import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.util.List;

/** Normalized content plus provider identity; null optional sections stay absent. */
public record TransferRecord(String sourceDocumentId, String sourceRevision, String documentUrl,
        TransferMarker marker, String title, StructuredMinutes minutes, List<TranscriptSegment> transcript,
        List<ParticipantSummary> participants, Participant participant, String renderedMarkdown, String storageKey) {
    public TransferRecord(String sourceDocumentId, String sourceRevision, String documentUrl,
            TransferMarker marker, String title, StructuredMinutes minutes, List<TranscriptSegment> transcript,
            List<ParticipantSummary> participants, Participant participant) {
        this(sourceDocumentId, sourceRevision, documentUrl, marker, title, minutes, transcript,
                participants, participant, null, null);
    }
    public TransferRecord {
        participants = List.copyOf(participants);
        transcript = transcript == null ? null : List.copyOf(transcript);
    }

    public TransferRecord withRenderedMarkdown(String markdown) {
        return new TransferRecord(sourceDocumentId, sourceRevision, documentUrl, marker, title, minutes,
                transcript, participants, participant, markdown, storageKey);
    }

    public TransferRecord withStorage(String pageUrl, String key) {
        return new TransferRecord(sourceDocumentId, sourceRevision, pageUrl, marker, title, minutes,
                transcript, participants, participant, renderedMarkdown, key);
    }
}
