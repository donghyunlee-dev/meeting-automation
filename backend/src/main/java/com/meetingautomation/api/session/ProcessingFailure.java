package com.meetingautomation.api.session;

/** Safe processing failure metadata; provider messages and response bodies are never retained. */
public record ProcessingFailure(String stage, String errorCode, boolean retryable) {
    public ProcessingFailure {
        if (stage == null || !stage.matches("[A-Z][A-Z0-9_]*")
                || errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("Processing failure is invalid.");
        }
    }
}
