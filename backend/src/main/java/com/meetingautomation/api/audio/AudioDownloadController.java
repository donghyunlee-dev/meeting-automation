package com.meetingautomation.api.audio;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions/{sessionId}/audio")
public final class AudioDownloadController {
    private final AudioDownloadService service;

    public AudioDownloadController(AudioDownloadService service) { this.service = service; }

    @GetMapping("/download")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(
            @PathVariable String sessionId) {
        AudioDownloadService.Download download = service.download(sessionId);
        return ResponseEntity.ok()
                .contentType(download.mimeType())
                .contentLength(download.assembly().bytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.filename()).build().toString())
                .body(download.body());
    }
}
