package com.meetingautomation.document.settings;

/** Only fixed, safe messages cross the API boundary. */
public final class SettingsException extends RuntimeException {
    private final String code;
    private final int status;
    public SettingsException(String code, int status) {
        super("문서 연결 설정을 확인해 주세요.");
        this.code = code;
        this.status = status;
    }
    public String code() { return code; }
    public int status() { return status; }
    public String category() {
        return status == 400 ? "VALIDATION" : status >= 500 ? "INTERNAL" : "CONFLICT";
    }
    public static SettingsException storage() { return new SettingsException("DOCUMENT_SETTINGS_UNAVAILABLE", 503); }
    public static SettingsException invalid() { return new SettingsException("VALIDATION_FAILED", 400); }
}
