package com.meetingautomation.api.session;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions")
public final class MeetingSessionController {
    private final MeetingSessionService service;

    public MeetingSessionController(MeetingSessionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MeetingSessionCreationResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateMeetingSessionRequest request) {
        MeetingSession session = service.create(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MeetingSessionCreationResponse.from(session, service.uploadPolicy()));
    }
}
