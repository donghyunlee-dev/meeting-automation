package com.meetingautomation.api.audio;

public final class AudioSessionNotFoundException extends RuntimeException {
    public AudioSessionNotFoundException() {
        super("Meeting session was not found.");
    }
}
