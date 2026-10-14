package com.meetingautomation.api.session;

public record PublishResponse(Data data) {
    public record Data(String sessionId, String status) { }
    static PublishResponse accepted(String sessionId) { return new PublishResponse(new Data(sessionId, "PUBLISHING")); }
}
