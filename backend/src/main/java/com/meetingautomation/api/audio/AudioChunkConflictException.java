package com.meetingautomation.api.audio;

public final class AudioChunkConflictException extends RuntimeException {
    public AudioChunkConflictException() {
        super("A different audio chunk already exists for this sequence.");
    }
}
