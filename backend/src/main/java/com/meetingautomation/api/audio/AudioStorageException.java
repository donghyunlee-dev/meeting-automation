package com.meetingautomation.api.audio;

public final class AudioStorageException extends RuntimeException {
    public AudioStorageException() {
        super("Audio chunk storage failed.");
    }
}
