package com.meetingautomation.api.session;

import java.util.List;
import java.util.ArrayList;
import com.meetingautomation.api.error.ApiValidationException;
import tools.jackson.databind.JsonNode;

public record SpeakerMappingRequest(List<Mapping> mappings) {
    public record Mapping(String speakerId, String participantId) { }

    public static SpeakerMappingRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) throw new ApiValidationException("request");
        JsonNode mappingsNode = body.get("mappings");
        if (mappingsNode == null || !mappingsNode.isArray()) throw new ApiValidationException("mappings");
        List<Mapping> mappings = new ArrayList<>();
        for (JsonNode node : mappingsNode) {
            if (node == null || !node.isObject()) throw new ApiValidationException("mappings");
            JsonNode speakerIdNode = node.get("speakerId");
            if (speakerIdNode == null || !speakerIdNode.isTextual()) throw new ApiValidationException("mappings");
            if (!node.has("participantId")) throw new ApiValidationException("mappings");
            JsonNode participantIdNode = node.get("participantId");
            String participantId;
            if (participantIdNode == null || participantIdNode.isNull()) {
                participantId = null;
            } else if (participantIdNode.isTextual()) {
                participantId = participantIdNode.textValue();
            } else {
                throw new ApiValidationException("mappings");
            }
            mappings.add(new Mapping(speakerIdNode.textValue(), participantId));
        }
        return new SpeakerMappingRequest(mappings);
    }
}
