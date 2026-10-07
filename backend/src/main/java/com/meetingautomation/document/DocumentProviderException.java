package com.meetingautomation.document;

/** Safe normalized provider failure; provider exception text is intentionally discarded. */
public final class DocumentProviderException extends RuntimeException {
    private final String code;
    private final String category;
    private final boolean retryable;
    private final int statusCode;

    private DocumentProviderException(String code, String message, boolean retryable, int statusCode) {
        super(message);
        this.code = code;
        this.category = "DOCUMENT_FAILURE";
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public static DocumentProviderException structureNotFound() {
        return new DocumentProviderException(
                "DOCUMENT_STRUCTURE_NOT_FOUND", "필수 문서 구조를 찾을 수 없습니다.", false,
                422);
    }

    public static DocumentProviderException participantListFailed(boolean retryable) {
        return new DocumentProviderException(
                "PARTICIPANT_LIST_FAILED", "참가자 목록을 불러올 수 없습니다.", retryable,
                502);
    }

    public static DocumentProviderException documentFailed(boolean retryable) {
        return new DocumentProviderException(
                "DOCUMENT_FAILED", "문서 작업을 완료할 수 없습니다.", retryable,
                502);
    }

    public String code() { return code; }
    public String category() { return category; }
    public boolean retryable() { return retryable; }
    public int statusCode() { return statusCode; }
}
