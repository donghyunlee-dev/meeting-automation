package com.meetingautomation.minutes;

public final class MinutesGenerationSettings {
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final String model;
    private final String apiKey;
    private final int connectTimeoutSeconds;
    private final int timeoutSeconds;

    public MinutesGenerationSettings(String model, String apiKey) {
        this(model, apiKey, DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    }

    public MinutesGenerationSettings(String model, String apiKey,
            int connectTimeoutSeconds, int timeoutSeconds) {
        this.model = model;
        this.apiKey = apiKey;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String model() { return model; }
    public String apiKey() { return apiKey; }
    public int connectTimeoutSeconds() { return connectTimeoutSeconds; }
    public int timeoutSeconds() { return timeoutSeconds; }

    @Override
    public String toString() {
        return "MinutesGenerationSettings[model=" + (model == null || model.isBlank() ? "<unset>" : model.strip())
                + ", apiKey=<redacted>, connectTimeoutSeconds=" + connectTimeoutSeconds
                + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
