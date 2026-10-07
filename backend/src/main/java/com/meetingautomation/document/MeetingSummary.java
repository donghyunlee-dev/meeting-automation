package com.meetingautomation.document;

import java.time.OffsetDateTime;
import java.util.List;

public record MeetingSummary(
        String documentId,
        String title,
        OffsetDateTime meetingAt,
        List<ParticipantSummary> participants,
        String documentUrl) {
    public MeetingSummary {
        participants = List.copyOf(participants);
    }
}
