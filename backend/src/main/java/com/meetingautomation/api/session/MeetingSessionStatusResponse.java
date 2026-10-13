package com.meetingautomation.api.session;

import java.util.List;

public record MeetingSessionStatusResponse(Data data) {
    public record Data(
            String sessionId,
            int version,
            String status,
            Meeting meeting,
            Processing processing,
            List<Object> speakers,
            List<Object> transcript,
            Object minutes,
            List<String> allowedActions) {
        public Data {
            speakers = List.copyOf(speakers);
            transcript = List.copyOf(transcript);
            allowedActions = List.copyOf(allowedActions);
        }
    }

    public record Meeting(List<String> participantIds) {
        public Meeting {
            participantIds = List.copyOf(participantIds);
        }
    }

    public record Processing(String stage, int progressPercent) {
    }

    static MeetingSessionStatusResponse from(MeetingSession session) {
        Processing processing = "PROCESSING".equals(session.status())
                ? new Processing("AUDIO_ASSEMBLY", 0)
                : null;
        return new MeetingSessionStatusResponse(new Data(session.sessionId(), session.version(), session.status(),
                new Meeting(session.meeting().participantIds()), processing, List.of(), List.of(), null, List.of()));
    }
}
