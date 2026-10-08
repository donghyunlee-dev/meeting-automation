package com.meetingautomation.api.participant;

import com.meetingautomation.document.Participant;
import java.util.List;

public record ParticipantListResponse(Data data) {
    public record Data(List<Participant> items) {
        public Data { items = List.copyOf(items); }
    }
}
