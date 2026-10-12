package com.meetingautomation.document.transfer;

/** Safe machine-readable failures at the provider transport boundary. */
public final class TransferAdapterException extends RuntimeException {
    public enum Code { CONFLICT, RECONCILIATION_REQUIRED }
    private final Code code;
    public TransferAdapterException(Code code) {
        super("Document transfer failed: " + code);
        this.code = code;
    }
    public Code code() { return code; }
}
