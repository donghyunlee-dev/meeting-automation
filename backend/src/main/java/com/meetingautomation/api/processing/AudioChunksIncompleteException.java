package com.meetingautomation.api.processing;

public final class AudioChunksIncompleteException extends RuntimeException {
    public AudioChunksIncompleteException() {
        super("Uploaded audio chunks do not match the expected sequence.");
    }
}
