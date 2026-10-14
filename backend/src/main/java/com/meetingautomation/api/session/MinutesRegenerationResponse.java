package com.meetingautomation.api.session;

public record MinutesRegenerationResponse(Data data) {
    public record Data(String sessionId, String status, String stage, String templateId) { }

    static MinutesRegenerationResponse accepted(String sessionId, String templateId) {
        return new MinutesRegenerationResponse(new Data(sessionId, "PROCESSING", "DRAFT_REGENERATION", templateId));
    }
}

