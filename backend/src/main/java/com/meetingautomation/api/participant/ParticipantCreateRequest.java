package com.meetingautomation.api.participant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ParticipantCreateRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") String email, String department) {
    public ParticipantCreateRequest(String name, String email) { this(name, email, ""); }
    public ParticipantCreateRequest {
        name = trim(name);
        email = trim(email);
        department = department == null ? "" : department.trim();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
