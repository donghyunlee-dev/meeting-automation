package com.meetingautomation.api.health;

public record IntegrationHealthResponse(Data data) {
    public record Data(
            DocumentHealthStatus document,
            IntegrationHealthStatus email,
            NotificationHealthStatus notification,
            IntegrationHealthStatus ai) {
    }

    public record NotificationHealthStatus(String provider, boolean configured, boolean reachable) {
    }
}
