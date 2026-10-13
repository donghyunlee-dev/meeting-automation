package com.meetingautomation.api.processing;

public record ProcessingStartResponse(Data data) {
    public record Data(String sessionId, String status, String stage) {
    }
}
