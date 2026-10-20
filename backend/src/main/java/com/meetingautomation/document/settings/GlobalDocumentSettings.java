package com.meetingautomation.document.settings;

import java.time.Instant;
import java.util.Map;

/** Private storage model. Never serialize this model in an HTTP response or a log. */
public record GlobalDocumentSettings(int schemaVersion, long version, Connection active, Draft draft,
        Operation operation, Map<String, Replay> idempotencyRecords, Map<String, Operation> operationHistory,
        SlackNotification slackNotification, EmailNotification emailNotification) {
    public GlobalDocumentSettings {
        operationHistory=operationHistory==null ? Map.of() : Map.copyOf(operationHistory);
        slackNotification=slackNotification==null ? SlackNotification.empty() : slackNotification;
        emailNotification=emailNotification==null ? EmailNotification.empty() : emailNotification;
    }
    public GlobalDocumentSettings(int schemaVersion,long version,Connection active,Draft draft,Operation operation,
            Map<String,Replay> records, Map<String,Operation> history, SlackNotification slackNotification) {
        this(schemaVersion,version,active,draft,operation,records,history,slackNotification,EmailNotification.empty());
    }
    public GlobalDocumentSettings(int schemaVersion,long version,Connection active,Draft draft,Operation operation,
            Map<String,Replay> records, Map<String,Operation> history) {
        this(schemaVersion,version,active,draft,operation,records,history,SlackNotification.empty(),EmailNotification.empty());
    }
    public GlobalDocumentSettings(int schemaVersion,long version,Connection active,Draft draft,Operation operation,Map<String,Replay> records) {
        this(schemaVersion,version,active,draft,operation,records,Map.of(),SlackNotification.empty(),EmailNotification.empty());
    }
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
            Instant expiresAt, com.meetingautomation.document.DocumentStructure preparedStructure) {
        public Draft(String draftId, long revision, String provider, Map<String, String> location,
                Map<String, String> credentials, String reuseExistingRootId, String state, TestResult testResult, Instant expiresAt) {
            this(draftId, revision, provider, location, credentials, reuseExistingRootId, state, testResult, expiresAt, null);
        }
        @Override public String toString() { return "Draft[redacted]"; }
    }
    public record TestResult(Instant testedAt, Instant expiresAt, boolean authenticated,
            boolean parentAccessible, String writeCapability) { }
    public record Operation(String operationId, String type, String status, String draftId, long draftRevision,
            String stage, Map<String, Creation> journal, String errorCode, boolean retryable, Instant updatedAt,
            boolean cancelRequested, long participantsTotal, long participantsCopied, long meetingsTotal,
            long meetingsCopied, Map<String, String> participantIdMap) {
        public Operation {
            journal=journal==null ? Map.of() : Map.copyOf(journal);
            stage=stage==null ? "PREFLIGHT" : stage;
            updatedAt=updatedAt==null ? Instant.EPOCH : updatedAt;
            participantIdMap=participantIdMap==null ? Map.of() : Map.copyOf(participantIdMap);
        }
        public Operation(String operationId, String type, String status, String draftId, long draftRevision,
                String stage, Map<String, Creation> journal, String errorCode, boolean retryable, Instant updatedAt) {
            this(operationId,type,status,draftId,draftRevision,stage,journal,errorCode,retryable,updatedAt,
                    false,0,0,0,0,Map.of());
        }
        public Operation(String operationId, String type, String status) {
            this(operationId, type, status, null, 0, "PREFLIGHT", Map.of(), null, false, Instant.EPOCH);
        }
        public Operation transition(String status, String stage, Map<String, Creation> journal, String error, boolean retryable, Instant now) {
            return new Operation(operationId, type, status, draftId, draftRevision, stage, Map.copyOf(journal), error,
                    retryable, now, "CANCELLED".equals(status) ? false : cancelRequested, participantsTotal, participantsCopied, meetingsTotal,
                    meetingsCopied, participantIdMap);
        }
        public Operation progress(String stage, long participantsTotal, long participantsCopied, long meetingsTotal,
                long meetingsCopied, Map<String, String> participantIdMap, Instant now) {
            return new Operation(operationId,type,status,draftId,draftRevision,stage,journal,errorCode,retryable,now,
                    cancelRequested,participantsTotal,participantsCopied,meetingsTotal,meetingsCopied,participantIdMap);
        }
        public Operation requestCancel(Instant now) {
            return new Operation(operationId,type,status,draftId,draftRevision,stage,journal,errorCode,retryable,now,
                    true,participantsTotal,participantsCopied,meetingsTotal,meetingsCopied,participantIdMap);
        }
        public Operation resume(Instant now) {
            return new Operation(operationId,type,"PENDING",draftId,draftRevision,"PREFLIGHT",journal,null,false,now,
                    false,participantsTotal,participantsCopied,meetingsTotal,meetingsCopied,participantIdMap);
        }
        @Override public String toString() { return "Operation[redacted]"; }
    }
    public record Creation(String parentId, String title, String marker, String pageId) { }
    public record Replay(String requestHmac, int status, String responseJson, long version) { }
    public record SlackNotification(String webhookUrl, boolean connectionVerified, Instant verifiedAt) {
        public static SlackNotification empty() { return new SlackNotification(null, false, null); }
        @Override public String toString() { return "SlackNotification[redacted]"; }
    }
    public record EmailNotification(String clientId, String clientSecret, String refreshToken, String senderAddress,
            boolean connectionVerified, Instant verifiedAt) {
        public static EmailNotification empty() { return new EmailNotification(null, null, null, null, false, null); }
        @Override public String toString() { return "EmailNotification[redacted]"; }
    }
}
