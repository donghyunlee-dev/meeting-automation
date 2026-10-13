package com.meetingautomation.api.session;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/meeting-sessions/{sessionId}/minutes")
public final class MinutesRegenerationController {
    private final MinutesRegenerationService service;

    public MinutesRegenerationController(MinutesRegenerationService service) {
        this.service = service;
    }

    @PostMapping("/regenerate")
    public ResponseEntity<MinutesRegenerationResponse> regenerate(
            @PathVariable String sessionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.regenerate(sessionId, ifMatch, idempotencyKey, body));
    }
}

