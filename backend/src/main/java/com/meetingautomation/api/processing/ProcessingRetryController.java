package com.meetingautomation.api.processing;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions/{sessionId}/processing")
public final class ProcessingRetryController {
    private final ProcessingRetryService service;
    public ProcessingRetryController(ProcessingRetryService service) { this.service = service; }

    @PostMapping("/retry")
    public ResponseEntity<ProcessingStartResponse> retry(@PathVariable String sessionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(sessionId, ifMatch, idempotencyKey));
    }
}
