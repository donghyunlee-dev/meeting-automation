package com.meetingautomation.api.session;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions/{sessionId}/deliveries/{deliveryId}/retry")
public final class DeliveryRetryController {
    private final DeliveryRetryService service;

    public DeliveryRetryController(DeliveryRetryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DeliveryRetryResponse> retry(@PathVariable String sessionId,
            @PathVariable String deliveryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(sessionId, deliveryId, idempotencyKey));
    }
}
