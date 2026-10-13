package com.meetingautomation.api.audio;

public final class AudioChunkInvalidException extends RuntimeException {
    public AudioChunkInvalidException() {
        super("Audio chunk request is invalid.");
    }
}
