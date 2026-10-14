package com.meetingautomation.api.meeting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meetings")
public final class MeetingListController {
    private final MeetingListService service;

    public MeetingListController(MeetingListService service) { this.service = service; }

    @GetMapping
    public MeetingListResponse listMeetings(@RequestParam(required = false) Integer limit) {
        return new MeetingListResponse(new MeetingListResponse.Data(service.list(limit)));
    }
}
