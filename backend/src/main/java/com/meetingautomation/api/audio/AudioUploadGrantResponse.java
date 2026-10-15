package com.meetingautomation.api.audio;

public record AudioUploadGrantResponse(Data data) {
    public record Data(String pathname, String clientPayload, long expiresAt) { }
}
