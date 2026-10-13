package com.meetingautomation.api.session;

import com.meetingautomation.document.StructuredMinutes;

public record MinutesUpdateResponse(Data data) {
    public record Data(int version, StructuredMinutes minutes) {
    }

    static MinutesUpdateResponse from(MeetingSession session) {
        return new MinutesUpdateResponse(new Data(session.version(), session.minutes()));
    }
}
