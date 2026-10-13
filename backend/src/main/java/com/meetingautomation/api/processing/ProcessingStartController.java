package com.meetingautomation.api.processing;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions/{sessionId}/process")
public final class ProcessingStartController {
    private final ProcessingStartService service;

    public ProcessingStartController(ProcessingStartService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProcessingStartResponse> start(
            @PathVariable String sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ProcessingStartRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.start(sessionId, idempotencyKey, request));
    }
}
