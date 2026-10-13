package com.meetingautomation.api.session;

public final class SessionStateConflictException extends RuntimeException {
    public SessionStateConflictException() {
        super("The session cannot perform this operation in its current state.");
    }
}
