package com.meetingautomation.transcription;

import java.nio.file.Path;
import java.io.InputStream;

public final class TranscriptionCommand {
    private final Path audioReference;
    private final InputStream audioStream;
    private final long audioBytes;
    private final String mimeType;
    private final String languageHint;
    private final long meetingDurationMs;

    public TranscriptionCommand(Path audioReference, String mimeType, String languageHint, long meetingDurationMs) {
        if (audioReference == null || mimeType == null || mimeType.isBlank() || meetingDurationMs < 1) {
            throw new IllegalArgumentException("Transcription command is invalid.");
        }
        this.audioReference = audioReference;
        this.audioStream = null;
        this.audioBytes = -1;
        this.mimeType = mimeType.strip();
        this.languageHint = languageHint == null || languageHint.isBlank() ? null : languageHint.strip();
        this.meetingDurationMs = meetingDurationMs;
    }

    public TranscriptionCommand(InputStream audioStream, long audioBytes, String mimeType, String languageHint,
            long meetingDurationMs) {
        if (audioStream == null || audioBytes < 1 || mimeType == null || mimeType.isBlank()
                || meetingDurationMs < 1) {
            throw new IllegalArgumentException("Transcription command is invalid.");
        }
        this.audioReference = null;
        this.audioStream = audioStream;
        this.audioBytes = audioBytes;
        this.mimeType = mimeType.strip();
        this.languageHint = languageHint == null || languageHint.isBlank() ? null : languageHint.strip();
        this.meetingDurationMs = meetingDurationMs;
    }

    public Path audioReference() { return audioReference; }
    public InputStream audioStream() { return audioStream; }
    public long audioBytes() { return audioBytes; }
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
