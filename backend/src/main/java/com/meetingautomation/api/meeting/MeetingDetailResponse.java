package com.meetingautomation.api.meeting;

import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.time.OffsetDateTime;
import java.util.List;

public record MeetingDetailResponse(Data data) {
    public record Data(String documentId, String title, OffsetDateTime meetingAt,
            List<ParticipantSummary> participants, StructuredMinutes minutes,
            List<TranscriptSegment> transcript, String documentUrl) {
        public Data {
            participants = List.copyOf(participants);
            transcript = List.copyOf(transcript);
        }
    }
}
