package com.meetingautomation.api.processing;

public final class AudioAssemblyException extends RuntimeException {
    private final String code;

    public AudioAssemblyException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
