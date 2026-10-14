package com.meetingautomation.api.session;

import com.meetingautomation.api.error.ApiValidationException;
import tools.jackson.databind.JsonNode;

record ReviewConfirmationRequest(boolean confirm) {
    static ReviewConfirmationRequest parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1) throw invalid();
        JsonNode confirm = body.get("confirm");
        if (confirm == null || !confirm.isBoolean() || !confirm.booleanValue()) throw invalid();
        return new ReviewConfirmationRequest(true);
    }

    private static ApiValidationException invalid() {
        return new ApiValidationException("confirm");
    }
}
