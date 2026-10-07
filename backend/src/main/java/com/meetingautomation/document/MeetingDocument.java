package com.meetingautomation.document;

import java.time.OffsetDateTime;
import java.util.List;

public record MeetingDocument(
        String documentId,
        String title,
        OffsetDateTime meetingAt,
        List<ParticipantSummary> participants,
        StructuredMinutes minutes,
        List<TranscriptSegment> transcript,
        String documentUrl) {
    public MeetingDocument {
        participants = List.copyOf(participants);
        transcript = List.copyOf(transcript);
    }
}
