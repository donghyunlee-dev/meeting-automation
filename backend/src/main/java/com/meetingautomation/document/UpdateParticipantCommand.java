package com.meetingautomation.document;

public record UpdateParticipantCommand(String name, String email, String department) {
    public UpdateParticipantCommand(String name, String email) { this(name, email, null); }
    public UpdateParticipantCommand {
        if (name == null && email == null && department == null) {
            throw new IllegalArgumentException("At least one participant field must be provided");
        }
    }
}
