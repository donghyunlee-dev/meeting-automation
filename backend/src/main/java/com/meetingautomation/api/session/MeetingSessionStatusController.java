package com.meetingautomation.api.session;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions")
public final class MeetingSessionStatusController {
    private final MeetingSessionStatusService service;

    public MeetingSessionStatusController(MeetingSessionStatusService service) {
        this.service = service;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<MeetingSessionStatusResponse> get(@PathVariable String sessionId) {
        return ResponseEntity.ok(service.get(sessionId));
    }
}
