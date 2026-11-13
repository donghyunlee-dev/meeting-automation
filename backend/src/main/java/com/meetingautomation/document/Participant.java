package com.meetingautomation.document;

public record Participant(String id, String name, String email, String department) {
    public Participant(String id, String name, String email) { this(id, name, email, ""); }
    public Participant { department = department == null ? "" : department.trim(); }
}
