package com.meetingautomation.api.meeting;

import com.meetingautomation.document.ParticipantSummary;
import java.time.OffsetDateTime;
import java.util.List;

public record MeetingListResponse(Data data) {
    public record Data(List<Item> items) {
        public Data { items = List.copyOf(items); }
    }

    public record Item(String documentId, String title, OffsetDateTime meetingAt,
            List<ParticipantSummary> participants, String documentUrl) {
        public Item { participants = List.copyOf(participants); }
    }
}
