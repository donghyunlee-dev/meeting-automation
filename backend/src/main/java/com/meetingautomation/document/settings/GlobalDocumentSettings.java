package com.meetingautomation.document.settings;

import java.time.Instant;
import java.util.Map;

/** Private storage model. Never serialize this model in an HTTP response or a log. */
public record GlobalDocumentSettings(int schemaVersion, long version, Connection active, Draft draft,
        Operation operation, Map<String, Replay> idempotencyRecords) {
    public static GlobalDocumentSettings empty() {
        return new GlobalDocumentSettings(1, 0, null, null, null, Map.of());
    }
    @Override public String toString() { return "GlobalDocumentSettings[redacted]"; }
    public record Connection(String connectionId, long connectionVersion, String provider,
            Map<String, String> location, Map<String, String> credentials, String rootId,
            String meetingsPageId, String participantsPageId, Instant createdAt) {
        @Override public String toString() { return "Connection[redacted]"; }
        public String rootUrl() {
            return "NOTION".equals(provider) ? "https://www.notion.so/" + rootId.replace("-", "")
                : location.get("baseUrl") + "/wiki/spaces/" + location.get("spaceId") + "/pages/" + rootId;
        }
    }
    public record Draft(String draftId, long revision, String provider, Map<String, String> location,
            Map<String, String> credentials, String reuseExistingRootId, String state, TestResult testResult,
            Instant expiresAt) {
        @Override public String toString() { return "Draft[redacted]"; }
    }
    public record TestResult(Instant testedAt, Instant expiresAt, boolean authenticated,
            boolean parentAccessible, String writeCapability) { }
    public record Operation(String operationId, String type, String status) { }
    public record Replay(String requestHmac, int status, String responseJson, long version) { }
}
