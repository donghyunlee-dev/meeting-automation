package com.meetingautomation.api.session;

import java.util.List;

public record SpeakerMappingResponse(Data data) {
    public record Data(int version, List<Mapping> mappings) {
        public Data {
            mappings = List.copyOf(mappings);
        }
    }
    public record Mapping(String speakerId, String participantId, String participantName) { }
}
