package com.meetingautomation.api.session;

public record ReviewConfirmationResponse(Data data) {
    public record Data(String sessionId, String status, int version) { }

    static ReviewConfirmationResponse confirmed(MeetingSession session) {
        return new ReviewConfirmationResponse(new Data(session.sessionId(), session.status(), session.version()));
    }
}
