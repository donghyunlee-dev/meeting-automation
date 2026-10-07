package com.meetingautomation.document;

public record UpdateParticipantCommand(String name, String email) {
    public UpdateParticipantCommand {
        if (name == null && email == null) {
            throw new IllegalArgumentException("At least one participant field must be provided");
        }
    }
}
