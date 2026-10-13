package com.meetingautomation.api.audio;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions/{sessionId}/audio")
public final class AudioUploadStatusController {
    private final AudioUploadStatusService service;

    public AudioUploadStatusController(AudioUploadStatusService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AudioUploadStatusResponse> getStatus(@PathVariable String sessionId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.getStatus(sessionId));
    }
}
