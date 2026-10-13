package com.meetingautomation.api.processing;

import tools.jackson.databind.JsonNode;

public record ProcessingStartRequest(JsonNode expectedChunks, JsonNode durationMs, String mimeType) {
}
