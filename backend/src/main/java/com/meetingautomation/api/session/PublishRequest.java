package com.meetingautomation.api.session;

import com.meetingautomation.api.error.ApiValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

record PublishRequest(List<String> emailRecipientParticipantIds, boolean notificationEnabled) {
    static PublishRequest parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2
                || !body.has("emailRecipientParticipantIds") || !body.has("notificationEnabled"))
            throw new ApiValidationException("body");
        JsonNode ids = body.get("emailRecipientParticipantIds");
        JsonNode notification = body.get("notificationEnabled");
        if (ids == null || !ids.isArray() || notification == null || !notification.isBoolean())
            throw new ApiValidationException("body");
        List<String> recipients = new ArrayList<>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || id.textValue().isBlank()) throw new ApiValidationException("emailRecipientParticipantIds");
            recipients.add(id.textValue());
        }
        if (new HashSet<>(recipients).size() != recipients.size())
            throw new ApiValidationException("emailRecipientParticipantIds");
        return new PublishRequest(List.copyOf(recipients), notification.booleanValue());
    }
}
