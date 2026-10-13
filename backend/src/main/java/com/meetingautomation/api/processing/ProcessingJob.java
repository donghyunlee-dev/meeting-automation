package com.meetingautomation.api.processing;

public record ProcessingJob(String sessionId, int expectedChunks, long durationMs, String mimeType) {
}
