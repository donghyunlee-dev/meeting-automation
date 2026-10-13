package com.meetingautomation.api.session;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/meeting-sessions")
public final class SpeakerMappingController {
    private final SpeakerMappingService service;

    public SpeakerMappingController(SpeakerMappingService service) {
        this.service = service;
    }

    @PutMapping("/{sessionId}/speaker-mappings")
    public ResponseEntity<SpeakerMappingResponse> replace(
            @PathVariable String sessionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String traceId,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.ok(service.replaceJson(sessionId, ifMatch, body, traceId));
    }
}
