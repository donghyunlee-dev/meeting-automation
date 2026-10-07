package com.meetingautomation.document;

import java.time.OffsetDateTime;
import java.util.List;

public record MeetingMetadata(
        String schemaVersion,
        String externalSessionId,
        OffsetDateTime meetingAt,
        String templateId,
        List<String> participantIds) {
    public MeetingMetadata {
        participantIds = List.copyOf(participantIds);
    }
}
