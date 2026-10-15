package com.meetingautomation.api.audio;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions/{sessionId}/audio/chunks")
public final class AudioChunkController {
    private final AudioChunkService service;
    private final AudioUploadGrantService grants;

    public AudioChunkController(AudioChunkService service, AudioUploadGrantService grants) {
        this.service = service;
        this.grants = grants;
    }

    @PostMapping("/{sequence}/upload-grant")
    public ResponseEntity<AudioUploadGrantResponse> uploadGrant(
            @PathVariable String sessionId,
            @PathVariable String sequence,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AudioUploadGrantRequest request) {
        return ResponseEntity.ok(grants.create(sessionId, sequence, request.mimeType(), request.bytes(), request.sha256(), idempotencyKey));
    }

    @PostMapping("/{sequence}/confirm")
    public ResponseEntity<AudioChunkUploadResponse> confirmUpload(
            @PathVariable String sessionId,
            @PathVariable String sequence,
            @RequestHeader(value = "X-Audio-SHA256", required = false) String sha256,
            @RequestHeader(value = "X-Audio-Byte-Length", required = false) String byteLength,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.ok(service.confirmClientUpload(sessionId, sequence, contentType, sha256, byteLength, idempotencyKey));
    }

    @PutMapping("/{sequence}")
    public ResponseEntity<AudioChunkUploadResponse> upload(
            @PathVariable String sessionId,
            @PathVariable String sequence,
            @RequestHeader(value = "X-Audio-SHA256", required = false) String sha256,
            @RequestHeader(value = "X-Audio-Byte-Length", required = false) String byteLength,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) throws IOException {
        AudioChunkUploadResponse response = service.upload(sessionId, sequence, request.getContentType(),
                sha256, byteLength, idempotencyKey, request.getContentLengthLong(), request.getInputStream());
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }
}
