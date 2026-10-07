package com.meetingautomation.api.config;

public record AppConfigResponse(Data data) {
    public record Data(
            Company company,
            Document document,
            Email email,
            Notification notification,
            Recording recording) {
    }

    public record Company(String id, String name, String timezone) {
    }

    public record Document(String provider, boolean configured) {
    }

    public record Email(boolean enabled, boolean configured) {
    }

    public record Notification(String provider, boolean enabled) {
    }

    public record Recording(int chunkDurationSeconds, int maxMeetingDurationMinutes) {
    }
}
