package com.meetingautomation.auth;

public final class OtpAuthenticationException extends RuntimeException {
    public enum Reason { LOCKED, COOLDOWN, ISSUE_IN_PROGRESS }

    private final Reason reason;
    private final int retryAfterSeconds;

    public OtpAuthenticationException(Reason reason, int retryAfterSeconds) {
        super(reason.name());
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Reason reason() { return reason; }
    public int retryAfterSeconds() { return retryAfterSeconds; }
}
