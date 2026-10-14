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
@RequestMapping("/api/v1/meeting-sessions/{sessionId}/processing")
public final class FailureFinalizeController {
    private final FailureFinalizeService service;
    public FailureFinalizeController(FailureFinalizeService service) { this.service = service; }

    @PostMapping("/finalize-failure")
    public ResponseEntity<FailureFinalizeResponse> finalizeFailure(@PathVariable String sessionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) FailureFinalizeRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.finalizeFailure(sessionId, ifMatch, idempotencyKey, request));
    }
}
