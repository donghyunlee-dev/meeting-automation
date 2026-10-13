package com.meetingautomation.minutes;

/** Safe provider/application failure without request, response, transcript, or credential details. */
public final class MinutesGenerationException extends RuntimeException {
    private final Code code;
    private final boolean retryable;

    public MinutesGenerationException(Code code, boolean retryable) {
        super(safeMessage(code));
        this.code = code;
        this.retryable = retryable;
    }

    public Code code() { return code; }
    public boolean retryable() { return retryable; }

    private static String safeMessage(Code code) {
        if (code == null) return "Minutes generation failed.";
        return switch (code) {
            case PROCESSING_FAILED -> "Minutes generation failed.";
            case CONFIGURATION_ERROR -> "Minutes provider configuration is invalid.";
            case AUTHENTICATION_FAILED -> "Minutes provider authentication failed.";
        };
    }

    public enum Code {
        PROCESSING_FAILED, CONFIGURATION_ERROR, AUTHENTICATION_FAILED
    }
}
