package com.meetingautomation.api.processing;

public final class FailureDocumentWriteException extends RuntimeException {
    public FailureDocumentWriteException() { super("Failure summary document could not be saved."); }
}
