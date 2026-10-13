package com.meetingautomation.api.processing;

public record ProcessingStartRequest(Integer expectedChunks, Long durationMs, String mimeType) {
}
