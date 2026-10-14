package com.meetingautomation.api.meeting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings")
public final class MeetingDetailController {
    private final MeetingDetailService service;

    public MeetingDetailController(MeetingDetailService service) {
        this.service = service;
    }

    @GetMapping("/{documentId}")
    public MeetingDetailResponse getMeeting(@PathVariable String documentId) {
        return new MeetingDetailResponse(service.get(documentId));
    }
}
