package com.meetingautomation.api.health;

public record DocumentHealthStatus(String provider, boolean configured, boolean reachable, boolean rootAccessible) {
    public static DocumentHealthStatus unavailable(String provider, boolean configured) {
        return new DocumentHealthStatus(provider, configured, false, false);
    }
}
