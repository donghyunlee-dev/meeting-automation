package com.meetingautomation.document.transfer;

import com.meetingautomation.document.StructuredMinutes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One codec for both providers. Error text never retains raw page content. */
public final class TransferCodec {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String HASH = "sha256:[0-9a-f]{64}";

    public enum Code {
        MISSING_MARKER, DUPLICATE_MARKER, MALFORMED_MARKER, UNSUPPORTED_SCHEMA,
        INVALID_IDENTITY, INVALID_RECORD, BROKEN_REFERENCE
    }
    public static final class ValidationException extends RuntimeException {
        private final Code code;
        public ValidationException(Code code) {
            super("Document transfer validation failed: " + code);
            this.code = code;
        }
        public Code code() { return code; }
    }

    /** Accepts ordered code-block text/fragments. Non-marker JSON blocks are ignored. */
    public TransferMarker parseMarker(List<String> codeBlockText) {
        if (codeBlockText == null || codeBlockText.isEmpty()) throw invalid(Code.MISSING_MARKER);
        try {
            String joined = String.join("", codeBlockText);
            List<JsonNode> markers = new ArrayList<>();
            try (var parser = JSON.createParser(joined)) {
                while (parser.nextToken() != null) {
                    JsonNode node = JSON.readTree(parser);
                    if (!node.isObject()) throw invalid(Code.MALFORMED_MARKER);
                    if ("meeting-automation".equals(node.path("format").asString())) markers.add(node);
                }
            }
            if (markers.isEmpty()) throw invalid(Code.MISSING_MARKER);
            if (markers.size() != 1) throw invalid(Code.DUPLICATE_MARKER);
            JsonNode n = markers.getFirst();
            if (!"1.0".equals(required(n, "schemaVersion"))) throw invalid(Code.UNSUPPORTED_SCHEMA);
            String type = required(n, "recordType");
            if (!Set.of("meeting", "participant").contains(type)) throw invalid(Code.MALFORMED_MARKER);
            boolean meeting = type.equals("meeting");
            List<String> ids = meeting ? strings(n, "participantIds") : List.of();
            List<TransferMarker.Speaker> speakers = new ArrayList<>();
            if (meeting) {
                JsonNode values = n.get("speakers");
                if (values == null || !values.isArray()) throw invalid(Code.MALFORMED_MARKER);
                for (JsonNode s : values) speakers.add(new TransferMarker.Speaker(required(s, "speakerId"),
                        required(s, "label"), optional(s, "participantId")));
            }
            TransferMarker.Failure failure = null;
            if (present(n, "failure")) {
                JsonNode f = n.get("failure");
                failure = new TransferMarker.Failure(required(f, "stage"), required(f, "errorCode"), required(f, "audioDisposition"));
            }
            TransferMarker.Transfer transfer = null;
            if (present(n, "transfer")) {
                JsonNode t = n.get("transfer");
                JsonNode idMap = t.get("participantIdMap");
                if (idMap == null || !idMap.isObject()) throw invalid(Code.MALFORMED_MARKER);
                Map<String, String> mapping = new LinkedHashMap<>();
                for (var entry : idMap.properties()) mapping.put(nonblank(entry.getKey()), text(entry.getValue()));
                transfer = new TransferMarker.Transfer(hash(t, "transferKey"), hash(t, "sourceConnectionFingerprint"),
                        required(t, "sourceDocumentId"), required(t, "sourceRevision"), hash(t, "canonicalDigest"), mapping);
            }
            return new TransferMarker("meeting-automation", type, "1.0",
                    meeting ? required(n, "externalSessionId") : null,
                    meeting ? OffsetDateTime.parse(required(n, "meetingAt")) : null,
                    meeting ? required(n, "templateId") : null, ids, speakers, failure,
                    meeting ? null : hash(n, "canonicalDigest"), transfer);
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid(Code.MALFORMED_MARKER);
        }
    }

    public String encodeMarker(TransferMarker marker) {
        Map<String, Object> value = markerContent(marker, Function.identity());
        if (marker.recordType().equals("participant")) value.put("canonicalDigest", marker.canonicalDigest());
        if (marker.transfer() != null) {
            var t = marker.transfer();
            value.put("transfer", Map.of("transferKey", t.transferKey(), "sourceConnectionFingerprint", t.sourceConnectionFingerprint(),
                    "sourceDocumentId", t.sourceDocumentId(), "sourceRevision", t.sourceRevision(),
                    "canonicalDigest", t.canonicalDigest(), "participantIdMap", t.participantIdMap()));
        }
        String result = canonicalJson(value);
        parseMarker(List.of(result)); // Validate writes by exactly the same contract as reads.
        return result;
    }

    public String canonicalDigest(TransferRecord record) {
        try {
            encodeMarker(record.marker());
            Function<String, String> sourceId = sourceIds(record.marker());
            Map<String, Object> content = markerContent(record.marker(), sourceId);
            if (record.marker().recordType().equals("participant")) {
                if (record.participant() == null) throw invalid(Code.INVALID_RECORD);
                content.put("name", nonblank(record.participant().name()));
                content.put("email", nonblank(record.participant().email()));
            } else {
                if (record.participant() != null) throw invalid(Code.INVALID_RECORD);
                content.put("title", nonblank(record.title()));
                content.put("participants", record.participants().stream().map(p ->
                        Map.of("id", sourceId.apply(p.id()), "name", nonblank(p.name()))).toList());
                if (record.minutes() != null) content.put("minutes", minutes(record.minutes(), sourceId));
                if (record.transcript() != null) content.put("transcript", record.transcript().stream().map(s ->
                        Map.of("segmentId", s.segmentId(), "speakerId", s.speakerId(), "startMs", s.startMs(),
                                "endMs", s.endMs(), "text", s.text())).toList());
            }
            return sha256(canonicalJson(content));
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid(Code.INVALID_RECORD);
        }
    }

    public String transferKey(String sourceConnectionFingerprint, String sourceDocumentId) {
        if (sourceConnectionFingerprint == null || !sourceConnectionFingerprint.matches(HASH)
                || sourceDocumentId == null || sourceDocumentId.isBlank()) throw invalid(Code.INVALID_IDENTITY);
        return sha256(canonicalJson(Map.of("sourceConnectionFingerprint", sourceConnectionFingerprint,
                "sourceDocumentId", sourceDocumentId)));
    }

    /** Inputs are provider type and configured root identity, never connection credentials or accounts. */
    public String connectionFingerprint(String providerType, String rootIdentity) {
        if (!Set.of("notion", "confluence").contains(providerType == null ? "" : providerType)
                || rootIdentity == null || rootIdentity.isBlank() || rootIdentity.contains("@")
                || rootIdentity.contains("://") || rootIdentity.toLowerCase(Locale.ROOT).startsWith("bearer "))
            throw invalid(Code.INVALID_IDENTITY);
        return sha256(canonicalJson(Map.of("providerType", providerType, "rootIdentity", rootIdentity)));
    }

    private static Function<String, String> sourceIds(TransferMarker marker) {
        if (marker.transfer() == null) return Function.identity();
        Map<String, String> reverse = new HashMap<>();
        marker.transfer().participantIdMap().forEach((source, target) -> {
            if (reverse.put(target, source) != null) throw invalid(Code.BROKEN_REFERENCE);
        });
        return target -> {
            if (target == null) return null;
            String source = reverse.get(target);
            if (source == null) throw invalid(Code.BROKEN_REFERENCE);
            return source;
        };
    }

    private static Map<String, Object> markerContent(TransferMarker m, Function<String, String> sourceId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", m.format()); value.put("recordType", m.recordType()); value.put("schemaVersion", m.schemaVersion());
        if ("meeting".equals(m.recordType())) {
            value.put("externalSessionId", m.externalSessionId());
            value.put("meetingAt", m.meetingAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            value.put("templateId", m.templateId());
            value.put("participantIds", m.participantIds().stream().map(sourceId).toList());
            value.put("speakers", m.speakers().stream().map(s -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("speakerId", s.speakerId()); fields.put("label", s.label()); fields.put("participantId", sourceId.apply(s.participantId()));
                return fields;
            }).toList());
            value.put("failure", m.failure() == null ? null : Map.of("stage", m.failure().stage(),
                    "errorCode", m.failure().errorCode(), "audioDisposition", m.failure().audioDisposition()));
        }
        return value;
    }

    private static Map<String, Object> minutes(StructuredMinutes m, Function<String, String> sourceId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("templateId", m.templateId()); fields.put("templateVersion", m.templateVersion()); fields.put("summary", m.summary());
        fields.put("discussionPoints", m.discussionPoints()); fields.put("decisions", m.decisions()); fields.put("followUps", m.followUps());
        fields.put("actionItems", m.actionItems().stream().map(a -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ownerParticipantId", sourceId.apply(a.ownerParticipantId())); item.put("task", a.task());
            item.put("dueDate", a.dueDate() == null ? null : a.dueDate().toString());
            return item;
        }).toList());
        return fields;
    }

    /** Recursively sort every object, retain array order, and emit UTF-8 JSON without whitespace. */
    private static String canonicalJson(Object value) { return JSON.writeValueAsString(sorted(value)); }
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put((String) key, sorted(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(TransferCodec::sorted).toList();
        return value;
    }
    private static String sha256(String canonicalJson) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean present(JsonNode n, String field) { return n.has(field) && !n.get(field).isNull(); }
    private static String optional(JsonNode n, String field) { return present(n, field) ? text(n.get(field)) : null; }
    private static String required(JsonNode n, String field) { return text(n.get(field)); }
    private static String text(JsonNode n) {
        if (n == null || !n.isString()) throw invalid(Code.MALFORMED_MARKER);
        return nonblank(n.asString());
    }
    private static String nonblank(String value) {
        if (value == null || value.isBlank()) throw invalid(Code.MALFORMED_MARKER);
        return value;
    }
    private static String hash(JsonNode n, String field) {
        String value = required(n, field);
        if (!value.matches(HASH)) throw invalid(Code.MALFORMED_MARKER);
        return value;
    }
    private static List<String> strings(JsonNode n, String field) {
        JsonNode values = n.get(field);
        if (values == null || !values.isArray()) throw invalid(Code.MALFORMED_MARKER);
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) result.add(text(value));
        return result;
    }
    private static ValidationException invalid(Code code) { return new ValidationException(code); }
}
