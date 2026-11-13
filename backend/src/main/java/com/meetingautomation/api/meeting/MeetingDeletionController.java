package com.meetingautomation.api.meeting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/meetings")
public final class MeetingDeletionController {
    private final MeetingDeletionService service;
    public MeetingDeletionController(MeetingDeletionService service) { this.service = service; }
    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String documentId) { service.delete(documentId); }
}
