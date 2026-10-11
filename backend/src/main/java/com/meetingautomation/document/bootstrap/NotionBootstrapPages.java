package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.DocumentProviderException;

import com.meetingautomation.document.notion.NotionPageHierarchyAdapter;
import java.util.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class NotionBootstrapPages implements BootstrapPages {
    private final BootstrapHttp http;
    public NotionBootstrapPages(RestClient.Builder builder, String token) {
        http = new BootstrapHttp(builder.baseUrl("https://api.notion.com")
            .defaultHeader("Authorization", "Bearer " + token)
            .defaultHeader("Notion-Version", NotionPageHierarchyAdapter.API_VERSION).build(), "https://api.notion.com");
    }
    @Override public List<Page> children(String parent) {
        List<Page> pages = new ArrayList<>();
        for (JsonNode block : blocks(parent)) {
            String type = block.path("type").asText();
            if (Set.of("child_page", "child_database").contains(type)) {
                pages.add(new Page(id(BootstrapHttp.required(block,"id")), parent,
                    BootstrapHttp.required(block.path(type),"title"), "child_page".equals(type) ? "page" : "database", null));
            }
        }
        return List.copyOf(pages);
    }
    @Override public Page read(String pageId) {
        JsonNode page = http.get("/v1/pages/" + id(pageId));
        if (!"page".equals(page.path("object").asText()) || page.path("archived").asBoolean() || page.path("in_trash").asBoolean()) throw BootstrapHttp.failure();
        String parent = id(BootstrapHttp.required(page.path("parent"),"page_id"));
        String title = null;
        for (JsonNode property : page.path("properties")) {
            if ("title".equals(property.path("type").asText()) || property.has("title")) {
                if (title != null || !property.path("title").isArray()) throw BootstrapHttp.failure();
                title = richText(property.path("title"));
            }
        }
        if (title == null) throw BootstrapHttp.failure();
        List<String> markers = new ArrayList<>();
        for (JsonNode block : blocks(pageId)) {
            if ("paragraph".equals(block.path("type").asText())) {
                String text = richText(block.path("paragraph").path("rich_text"));
                if (text.startsWith("meeting-automation/bootstrap/")) markers.add(text);
            }
        }
        return new Page(id(BootstrapHttp.required(page,"id")), parent, title, "page", markers.size() == 1 ? markers.getFirst() : null);
    }
    @Override public String create(String parent, String title, String marker) {
        var body = Map.of("parent", Map.of("page_id",id(parent)),
            "properties", Map.of("title",Map.of("title",List.of(Map.of("type","text","text",Map.of("content",title))))),
            "children",List.of(Map.of("object","block","type","paragraph","paragraph",Map.of("rich_text",
                List.of(Map.of("type","text","text",Map.of("content",marker)))))));
        JsonNode created=http.post("/v1/pages",body);
        try { return id(BootstrapHttp.required(created,"id")); }
        catch (DocumentProviderException unusableResponse) { throw BootstrapHttp.reconciliation(); }
    }
    private List<JsonNode> blocks(String parent) {
        List<JsonNode> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String cursor = null;
        do {
            String path = "/v1/blocks/" + id(parent) + "/children?page_size=100";
            if (cursor != null) path += "&start_cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode body = http.get(path);
            if (!body.path("results").isArray() || !body.path("has_more").isBoolean()) throw BootstrapHttp.failure();
            body.path("results").forEach(result::add);
            cursor = body.path("has_more").asBoolean() ? BootstrapHttp.required(body,"next_cursor") : null;
            if (cursor != null && !seen.add(cursor)) throw BootstrapHttp.failure();
        } while (cursor != null);
        return result;
    }
    private static String richText(JsonNode runs) {
        if (!runs.isArray()) throw BootstrapHttp.failure();
        StringBuilder text = new StringBuilder();
        for (JsonNode run : runs) text.append(run.has("plain_text") ? run.path("plain_text").asText() : run.path("text").path("content").asText());
        return text.toString();
    }
    private static String id(String value) {
        String compact = value == null ? "" : value.replace("-", "").toLowerCase(Locale.ROOT);
        if (!compact.matches("[a-f0-9]{32}")) throw BootstrapHttp.failure();
        return compact.substring(0,8)+"-"+compact.substring(8,12)+"-"+compact.substring(12,16)+"-"+compact.substring(16,20)+"-"+compact.substring(20);
    }
}
