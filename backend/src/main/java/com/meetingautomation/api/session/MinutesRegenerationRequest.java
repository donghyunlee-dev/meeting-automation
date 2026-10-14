package com.meetingautomation.api.session;

import com.meetingautomation.api.error.ApiValidationException;
import tools.jackson.databind.JsonNode;

record MinutesRegenerationRequest(String templateId) {
    static MinutesRegenerationRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        JsonNode templateId = body.get("templateId");
        if (templateId == null || !templateId.isTextual() || templateId.asString().isBlank()) throw invalid();
        return new MinutesRegenerationRequest(templateId.stringValue().trim());
    }

    private static ApiValidationException invalid() {
        return new ApiValidationException("templateId");
    }
}

