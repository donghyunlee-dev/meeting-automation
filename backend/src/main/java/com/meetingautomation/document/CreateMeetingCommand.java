package com.meetingautomation.document;

import java.time.OffsetDateTime;
import java.util.List;

public record CreateMeetingCommand(
        String title,
        OffsetDateTime meetingAt,
        List<ParticipantSummary> participants,
        StructuredMinutes minutes,
        List<TranscriptSegment> transcript,
        MeetingMetadata metadata) {
    public CreateMeetingCommand {
        participants = List.copyOf(participants);
        transcript = List.copyOf(transcript);
    }
}
