package com.meetingautomation.api.config;

public record EmailConnectionTestResponse(Data data) {
    public record Data(boolean connected, String message) { }
}
