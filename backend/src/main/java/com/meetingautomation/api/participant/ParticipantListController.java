package com.meetingautomation.api.participant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/participants")
public final class ParticipantListController {
    private final ParticipantListService participantListService;

    public ParticipantListController(ParticipantListService participantListService) {
        this.participantListService = participantListService;
    }

    @GetMapping
    public ParticipantListResponse listParticipants() {
        return new ParticipantListResponse(new ParticipantListResponse.Data(participantListService.listParticipants()));
    }
}
