package com.meetingautomation.document.notion;

import com.meetingautomation.document.DocumentProviderException;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Validates every target before archiving, leaving the index as the retry anchor until last. */
final class NotionMeetingDeletion {
    interface Access {
        List<JsonNode> blocks(String id);
        JsonNode page(String id);
        void archive(String id, boolean page);
    }
    private final Access access;
    NotionMeetingDeletion(Access access) { this.access = access; }

    void delete(String documentId, String dataId, String meetingsId, String indexId) {
        var tables = access.blocks(indexId).stream().filter(b -> "table".equals(b.path("type").asText())).toList();
        if (tables.size() != 1) throw DocumentProviderException.documentFailed(false);
        List<JsonNode> rows = access.blocks(required(tables.getFirst(), "id"));
        JsonNode target = null;
        NotionMeetingIndex.Entry entry = null;
        boolean header = false;
        for (JsonNode row : rows) {
            if (!"table_row".equals(row.path("type").asText())) continue;
            if (!header) { header = true; continue; }
            var candidate = NotionMeetingIndex.readRow(row);
            if (documentId.equals(candidate.summary().documentId())) {
                if (target != null) throw DocumentProviderException.documentFailed(false);
                target = row; entry = candidate;
            }
        }
        if (target == null) throw DocumentProviderException.meetingNotFound();
        String rowId = required(target, "id");
        JsonNode raw = access.page(documentId);
        requireParent(raw, documentId, dataId);
        String humanId = humanId(entry.summary().documentUrl());
        if (sameId(documentId, humanId)) throw DocumentProviderException.documentFailed(false);
        JsonNode human = access.page(humanId);
        requireParent(human, humanId, meetingsId);
        if (!archived(human)) access.archive(humanId, true);
        if (!archived(raw)) access.archive(documentId, true);
        access.archive(rowId, false);
    }
    private static boolean archived(JsonNode page) {
        return page.path("archived").asBoolean(false) || page.path("in_trash").asBoolean(false);
    }
    private static void requireParent(JsonNode page, String id, String parent) {
        if (!sameId(id, required(page, "id")) || !"page_id".equals(page.path("parent").path("type").asText())
                || !sameId(parent, page.path("parent").path("page_id").asText())) {
            throw DocumentProviderException.documentFailed(false);
        }
    }
    private static boolean sameId(String a, String b) { return a != null && b != null && a.replace("-", "").equalsIgnoreCase(b.replace("-", "")); }
    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw DocumentProviderException.documentFailed(false);
        return value;
    }
    private static String humanId(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || host == null
                    || !(host.equalsIgnoreCase("notion.so") || host.equalsIgnoreCase("www.notion.so"))) {
                throw DocumentProviderException.documentFailed(false);
            }
            var match = Pattern.compile("([0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$").matcher(uri.getPath());
            if (!match.find()) throw DocumentProviderException.documentFailed(false);
            String id = match.group(1).replace("-", "");
            return id.substring(0,8)+"-"+id.substring(8,12)+"-"+id.substring(12,16)+"-"+id.substring(16,20)+"-"+id.substring(20);
        } catch (RuntimeException invalid) { throw DocumentProviderException.documentFailed(false, invalid); }
    }
}
