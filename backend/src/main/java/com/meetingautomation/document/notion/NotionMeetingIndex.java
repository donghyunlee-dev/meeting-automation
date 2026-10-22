package com.meetingautomation.document.notion;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.MeetingSummary;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.transfer.TransferRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Compact list metadata stored as rows in the Notion Meeting Index table. */
public final class NotionMeetingIndex {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> COLUMNS = List.of(
            "Meeting page ID", "Session ID", "Title", "Meeting time", "Participants JSON", "Document URL");

    private NotionMeetingIndex() { }

    public record Entry(String sessionId, MeetingSummary summary) { }

    public static Map<String, Object> tableBlock() {
        List<Object> headerCells = COLUMNS.stream().map(NotionMeetingIndex::cell).map(value -> (Object) value).toList();
        Map<String, Object> header = Map.of("object", "block", "type", "table_row",
                "table_row", Map.of("cells", headerCells));
        return Map.of("object", "block", "type", "table", "table",
                Map.of("table_width", COLUMNS.size(), "has_column_header", true, "has_row_header", false,
                        "children", List.of(header)));
    }

    public static Map<String, Object> rowBlock(TransferRecord record) {
        String participants;
        try {
            participants = JSON.writeValueAsString(record.participants().stream()
                    .map(person -> Map.of("id", person.id(), "name", person.name())).toList());
        } catch (Exception invalidRecord) {
            throw DocumentProviderException.documentFailed(false);
        }
        List<Object> cells = List.of(record.sourceDocumentId(), record.marker().externalSessionId(), record.title(),
                record.marker().meetingAt().toString(), participants, record.documentUrl() == null ? "" : record.documentUrl())
                .stream().map(NotionMeetingIndex::cell).map(value -> (Object) value).toList();
        return Map.of("object", "block", "type", "table_row", "table_row", Map.of("cells", cells));
    }

    public static Entry readRow(JsonNode block) {
        try {
            JsonNode cells = block.path("table_row").path("cells");
            if (!"table_row".equals(block.path("type").asText()) || !cells.isArray() || cells.size() != COLUMNS.size()) {
                throw DocumentProviderException.documentFailed(false);
            }
            String documentId = readCell(cells.get(0));
            String sessionId = readCell(cells.get(1));
            String title = readCell(cells.get(2));
            OffsetDateTime meetingAt = OffsetDateTime.parse(readCell(cells.get(3)));
            List<ParticipantSummary> participants = readParticipants(readCell(cells.get(4)));
            String url = readCell(cells.get(5));
            if (blank(documentId) || blank(sessionId) || blank(title)) throw DocumentProviderException.documentFailed(false);
            return new Entry(sessionId, new MeetingSummary(documentId, title, meetingAt, participants, blank(url) ? null : url));
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (RuntimeException malformedRow) {
            throw DocumentProviderException.documentFailed(false);
        }
    }

    private static List<ParticipantSummary> readParticipants(String json) {
        JsonNode value = JSON.readTree(json);
        if (value == null || !value.isArray()) throw DocumentProviderException.documentFailed(false);
        List<ParticipantSummary> result = new ArrayList<>();
        for (JsonNode item : value) {
            String id = item.path("id").asText(null);
            String name = item.path("name").asText(null);
            if (blank(id) || blank(name)) throw DocumentProviderException.documentFailed(false);
            result.add(new ParticipantSummary(id, name));
        }
        return List.copyOf(result);
    }

    private static List<Object> cell(String text) {
        List<Object> runs = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + 2000, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            runs.add(Map.of("type", "text", "text", Map.of("content", text.substring(start, end))));
            start = end;
        }
        if (runs.isEmpty()) runs.add(Map.of("type", "text", "text", Map.of("content", "")));
        return List.copyOf(runs);
    }

    private static String readCell(JsonNode cell) {
        if (cell == null || !cell.isArray()) throw DocumentProviderException.documentFailed(false);
        StringBuilder result = new StringBuilder();
        for (JsonNode run : cell) {
            JsonNode plainText = run.get("plain_text");
            JsonNode content = run.path("text").get("content");
            if (plainText != null && plainText.isTextual()) result.append(plainText.asText());
            else if (content != null && content.isTextual()) result.append(content.asText());
            else throw DocumentProviderException.documentFailed(false);
        }
        return result.toString();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
