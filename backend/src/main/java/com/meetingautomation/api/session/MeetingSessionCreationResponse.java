package com.meetingautomation.api.session;

public record MeetingSessionCreationResponse(Data data) {
    public record Data(String sessionId, int version, String status, UploadPolicy uploadPolicy) {
    }

    static MeetingSessionCreationResponse from(MeetingSession session, UploadPolicy policy) {
        return new MeetingSessionCreationResponse(
                new Data(session.sessionId(), session.version(), session.status(), policy));
    }
}
