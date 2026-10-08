package com.meetingautomation.api.participant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/participants")
public final class ParticipantController {
    private final ParticipantService participantService;

    public ParticipantController(ParticipantService participantService) {
        this.participantService = participantService;
    }

    @GetMapping
    public ParticipantListResponse listParticipants() {
        return new ParticipantListResponse(new ParticipantListResponse.Data(participantService.listParticipants()));
    }

    @PostMapping
    public ResponseEntity<ParticipantCreateResponse> createParticipant(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody ParticipantCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ParticipantCreateResponse(
                        participantService.createParticipant(idempotencyKey, request)));
    }
}
