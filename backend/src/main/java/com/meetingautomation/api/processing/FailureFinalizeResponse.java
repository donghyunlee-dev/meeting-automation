package com.meetingautomation.api.processing;

public record FailureFinalizeResponse(Data data) {
    public record Data(String sessionId, String status, int version, String audioDisposition,
            String documentId, String documentUrl) { }
}
