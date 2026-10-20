package com.meetingautomation.api.config;

public record SlackConnectionTestResponse(Data data) {
    public record Data(boolean sent, String message) { }
}
