package com.meetingautomation.api.health;

public record IntegrationHealthStatus(boolean configured, boolean reachable) {
    public static IntegrationHealthStatus unavailable() {
        return new IntegrationHealthStatus(false, false);
    }
}
