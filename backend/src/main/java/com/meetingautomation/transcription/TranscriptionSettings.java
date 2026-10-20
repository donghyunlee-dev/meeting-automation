package com.meetingautomation.transcription;

public final class TranscriptionSettings {
    public static final long DEFAULT_MAX_AUDIO_BYTES = 25L * 1024 * 1024;
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_TIMEOUT_SECONDS = 1800;

    private final String model;
    private final String apiKey;
    private final long maxAudioBytes;
    private final int connectTimeoutSeconds;
    private final int timeoutSeconds;

    public TranscriptionSettings(String model, String apiKey) {
        this(model, apiKey, DEFAULT_MAX_AUDIO_BYTES,
                DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    }

    public TranscriptionSettings(String model, String apiKey, long maxAudioBytes) {
        this(model, apiKey, maxAudioBytes,
                DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    }

    public TranscriptionSettings(String model, String apiKey, long maxAudioBytes,
            int connectTimeoutSeconds, int timeoutSeconds) {
        this.model = model;
        this.apiKey = apiKey;
        this.maxAudioBytes = maxAudioBytes;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String model() { return model; }
    public String apiKey() { return apiKey; }
    public long maxAudioBytes() { return maxAudioBytes; }
    public int connectTimeoutSeconds() { return connectTimeoutSeconds; }
    public int timeoutSeconds() { return timeoutSeconds; }

    @Override
    public String toString() {
        return "TranscriptionSettings[model=" + (model == null || model.isBlank() ? "<unset>" : model.strip())
                + ", apiKey=<redacted>, maxAudioBytes=" + maxAudioBytes
                + ", connectTimeoutSeconds=" + connectTimeoutSeconds
                + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
