package com.meetingautomation.transcription;

import java.nio.file.Path;

public final class TranscriptionCommand {
    private final Path audioReference;
    private final String mimeType;
    private final String languageHint;
    private final long meetingDurationMs;

    public TranscriptionCommand(Path audioReference, String mimeType, String languageHint, long meetingDurationMs) {
        if (audioReference == null || mimeType == null || mimeType.isBlank() || meetingDurationMs < 1) {
            throw new IllegalArgumentException("Transcription command is invalid.");
        }
        this.audioReference = audioReference;
        this.mimeType = mimeType.strip();
        this.languageHint = languageHint == null || languageHint.isBlank() ? null : languageHint.strip();
        this.meetingDurationMs = meetingDurationMs;
    }

    public Path audioReference() { return audioReference; }
    public String mimeType() { return mimeType; }
    public String languageHint() { return languageHint; }
    public long meetingDurationMs() { return meetingDurationMs; }

    @Override
    public String toString() {
        return "TranscriptionCommand[audioReference=<redacted>, mimeType=" + mimeType
                + ", languageHint=" + (languageHint == null ? "<none>" : languageHint)
                + ", meetingDurationMs=" + meetingDurationMs + "]";
    }
}
