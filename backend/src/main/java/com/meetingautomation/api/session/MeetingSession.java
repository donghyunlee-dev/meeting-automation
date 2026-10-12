package com.meetingautomation.api.session;

import java.util.List;

public record MeetingSession(
        String sessionId,
        int version,
        String status,
        long documentConnectionVersion,
        Meeting meeting) {
    public record Meeting(String title, String templateId, List<String> participantIds, String timezone) {
        public Meeting {
            participantIds = List.copyOf(participantIds);
        }
    }
}
