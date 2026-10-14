package com.meetingautomation.api.session;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/meeting-sessions")
final class PublishController {
    private final PublishService service;
    PublishController(PublishService service) { this.service = service; }

    @PostMapping("/{sessionId}/publish")
    ResponseEntity<PublishResponse> publish(@PathVariable String sessionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        return ResponseEntity.accepted().body(service.publish(sessionId, ifMatch, key, body));
    }
}
