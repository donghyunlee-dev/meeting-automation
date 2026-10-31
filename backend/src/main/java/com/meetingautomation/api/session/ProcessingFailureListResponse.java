package com.meetingautomation.api.session;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public record ProcessingFailureListResponse(Data data) {
    public record Data(List<Item> items) {
        public Data { items = List.copyOf(items); }
    }

    public record Item(String sessionId, int version, String title, OffsetDateTime meetingAt,
            String stage, String errorCode, Instant audioExpiresAt, List<String> allowedActions) {
        public Item { allowedActions = List.copyOf(allowedActions); }
    }
}
