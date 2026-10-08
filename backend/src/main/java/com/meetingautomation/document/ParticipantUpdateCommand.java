package com.meetingautomation.document;

/** Partial field update plus the roster snapshot used to form a complete result. */
public record ParticipantUpdateCommand(String name, String email, Participant existing) {
    public ParticipantUpdateCommand {
        if (existing == null || (name == null && email == null)) {
            throw new IllegalArgumentException("A participant snapshot and at least one field are required");
        }
    }

    public String updatedName() {
        return name == null ? existing.name() : name;
    }

    public String updatedEmail() {
        return email == null ? existing.email() : email;
    }
}
