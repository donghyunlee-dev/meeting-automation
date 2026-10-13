package com.meetingautomation.transcription;

public final class TranscriptionSettings {
    public static final long DEFAULT_MAX_AUDIO_BYTES = 25L * 1024 * 1024;

    private final String model;
    private final String apiKey;
    private final long maxAudioBytes;

    public TranscriptionSettings(String model, String apiKey) {
        this(model, apiKey, DEFAULT_MAX_AUDIO_BYTES);
    }

    public TranscriptionSettings(String model, String apiKey, long maxAudioBytes) {
        this.model = model;
        this.apiKey = apiKey;
        this.maxAudioBytes = maxAudioBytes;
    }

    public String model() { return model; }
    public String apiKey() { return apiKey; }
    public long maxAudioBytes() { return maxAudioBytes; }

    @Override
    public String toString() {
        return "TranscriptionSettings[model=" + (model == null || model.isBlank() ? "<unset>" : model.strip())
                + ", apiKey=<redacted>, maxAudioBytes=" + maxAudioBytes + "]";
    }
}
