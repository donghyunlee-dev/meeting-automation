package com.meetingautomation.api.config;

public record EmailTestMessageResponse(Data data) {
    public record Data(boolean sent, String message) { }
}
