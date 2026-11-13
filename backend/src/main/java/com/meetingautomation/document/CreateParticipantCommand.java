package com.meetingautomation.document;

public record CreateParticipantCommand(String name, String email, String department) {
    public CreateParticipantCommand(String name, String email) { this(name, email, ""); }
    public CreateParticipantCommand { department = department == null ? "" : department.trim(); }
}
