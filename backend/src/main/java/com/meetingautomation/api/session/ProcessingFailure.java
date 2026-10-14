package com.meetingautomation.api.session;

import java.time.Instant;

/** Safe processing failure metadata; provider messages and response bodies are never retained. */
public record ProcessingFailure(String stage, String errorCode, boolean retryable, boolean audioAvailable,
        Instant audioExpiresAt, boolean downloadCompleted, boolean retryInputAvailable) {
    public ProcessingFailure(String stage, String errorCode, boolean retryable) {
        this(stage, errorCode, retryable, false, null, false, false);
    }

    public ProcessingFailure {
        if (stage == null || !stage.matches("[A-Z][A-Z0-9_]*")
                || errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("Processing failure is invalid.");
        }
    }
}
