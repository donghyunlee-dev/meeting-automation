package com.meetingautomation.transcription;

public final class TranscriptionException extends RuntimeException {
    private final Code code;
    private final boolean retryable;

    public TranscriptionException(Code code, boolean retryable) {
        super(safeMessage(code));
        this.code = code;
        this.retryable = retryable;
    }

    public Code code() { return code; }
    public boolean retryable() { return retryable; }

    private static String safeMessage(Code code) {
        if (code == null) return "Transcription failed.";
        return switch (code) {
            case PROCESSING_FAILED -> "Transcription processing failed.";
            case INVALID_AUDIO -> "Audio could not be transcribed.";
            case CONFIGURATION_ERROR -> "Transcription provider configuration is invalid.";
            case AUTHENTICATION_FAILED -> "Transcription provider authentication failed.";
        };
    }

    public enum Code {
        PROCESSING_FAILED, INVALID_AUDIO, CONFIGURATION_ERROR, AUTHENTICATION_FAILED
    }
}
