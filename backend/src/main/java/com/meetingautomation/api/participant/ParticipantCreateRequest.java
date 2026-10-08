package com.meetingautomation.api.participant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ParticipantCreateRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") String email) {
    public ParticipantCreateRequest {
        name = trim(name);
        email = trim(email);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
