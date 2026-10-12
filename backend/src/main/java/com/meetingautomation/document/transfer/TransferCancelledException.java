package com.meetingautomation.document.transfer;

/** Raised only at a safe point after the current external request has been reconciled. */
public final class TransferCancelledException extends RuntimeException {
    public TransferCancelledException() { super("Document transfer cancelled at a safe point."); }
}
