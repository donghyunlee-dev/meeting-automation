package com.meetingautomation.document.transfer;

/** A safe, content-free failure describing why a document transfer could not complete. */
public final class TransferConflictException extends RuntimeException {
    public enum Code {
        DUPLICATE_TRANSFER_KEY, DUPLICATE_EXTERNAL_SESSION_ID, UNMARKED_COLLISION,
        TRANSFER_KEY_MISMATCH, DIGEST_MISMATCH, INVALID_TARGET, INVALID_IMPORT_RESULT,
        RECONCILIATION_REQUIRED, SOURCE_CHANGED
    }

    private final Code code;

    public TransferConflictException(Code code) {
        super("Document transfer stopped: " + code);
        this.code = code;
    }

    public Code code() { return code; }
}
