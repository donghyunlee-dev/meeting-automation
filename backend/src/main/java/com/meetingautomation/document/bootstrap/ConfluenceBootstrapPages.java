package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.DocumentProviderException;

import java.util.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

public final class ConfluenceBootstrapPages implements BootstrapPages {
    private final BootstrapHttp http;
    private final String spaceId;
    public ConfluenceBootstrapPages(RestClient.Builder builder, String baseUrl, String spaceId, String email, String token) {
        this.spaceId = numeric(spaceId);
        http = new BootstrapHttp(builder.baseUrl(baseUrl).defaultHeaders(h -> h.setBasicAuth(email,token)).build(),baseUrl);
    }
    @Override public List<Page> children(String parent) {
        if (parent==null) return rootCandidates();
        String path = "/wiki/api/v2/pages/"+numeric(parent)+"/direct-children?limit=100";
        List<Page> pages = new ArrayList<>(); Set<String> seen = new HashSet<>();
        while (path != null) {
            if (!seen.add(path)) throw BootstrapHttp.failure();
            JsonNode body = http.get(path);
            if (!body.path("results").isArray()) throw BootstrapHttp.failure();
            for (JsonNode child : body.path("results")) {
                String type = BootstrapHttp.required(child,"type");
                String childParent = child.hasNonNull("parentId") ? numeric(child.path("parentId").asText()) : parent;
                if (!Objects.equals(parent,childParent)) continue;
                pages.add(new Page(numeric(BootstrapHttp.required(child,"id")),parent,BootstrapHttp.required(child,"title"),type,null));
            }
            var next = body.path("_links").path("next");
            if (!next.isMissingNode() && !next.isNull() && (!next.isTextual() || next.asText().isBlank())) throw BootstrapHttp.failure();
            path = next.isTextual() ? java.net.URI.create(path).resolve(next.asText()).toString() : null;
        }
        return List.copyOf(pages);
    }
    private List<Page> rootCandidates() {
        JsonNode space=http.get("/wiki/api/v2/spaces/"+spaceId);
        if (!spaceId.equals(BootstrapHttp.required(space,"id"))) throw BootstrapHttp.failure();
        String key=BootstrapHttp.required(space,"key");
        if (!key.matches("[A-Za-z0-9_~-]+")) throw BootstrapHttp.failure();
        String cql="space = \""+key+"\" AND title ~ \"\\\"Meeting Automation\\\"\" AND type IN (page, folder, database)";
        String path="/wiki/rest/api/search?cql="+java.net.URLEncoder.encode(cql,java.nio.charset.StandardCharsets.UTF_8)
            +"&expand=content.ancestors,content.space&limit=100";
        List<Page> roots=new ArrayList<>(); Set<String> seen=new HashSet<>();
        while(path!=null) {
            if(!seen.add(path)) throw BootstrapHttp.failure();
            JsonNode body=http.get(path);
            if(!body.path("results").isArray()) throw BootstrapHttp.failure();
            for(JsonNode result:body.path("results")) {
                JsonNode content=result.path("content");
                if(!content.path("ancestors").isArray()) throw BootstrapHttp.failure();
                if(!content.path("ancestors").isEmpty()) continue;
                if(!spaceId.equals(content.path("space").path("id").asText())) throw BootstrapHttp.failure();
                roots.add(new Page(numeric(BootstrapHttp.required(content,"id")),null,
                    BootstrapHttp.required(content,"title"),BootstrapHttp.required(content,"type"),null));
            }
            JsonNode next=body.path("_links").path("next");
            if(!next.isMissingNode() && !next.isNull() && (!next.isTextual() || next.asText().isBlank())) throw BootstrapHttp.failure();
            path=next.isTextual()?java.net.URI.create(path).resolve(next.asText()).toString():null;
        }
        return List.copyOf(roots);
    }
    @Override public Page read(String id) {
        JsonNode page = http.get("/wiki/api/v2/pages/"+numeric(id)+"?body-format=storage");
        if (!spaceId.equals(page.path("spaceId").asText()) || !"current".equals(page.path("status").asText())
            || (page.hasNonNull("parentId") && !"page".equals(page.path("parentType").asText()))) throw BootstrapHttp.failure();
        String parent = page.hasNonNull("parentId") ? numeric(page.path("parentId").asText()) : null;
        String body = page.path("body").path("storage").path("value").asText();
        var matcher = java.util.regex.Pattern.compile("<p(?:\\s[^>]*)?>\\s*(meeting-automation/bootstrap/[A-Za-z0-9_\\-/]+)\\s*</p>").matcher(body);
        String marker = matcher.find() ? matcher.group(1) : null;
        if (matcher.find()) marker=null;
        return new Page(numeric(BootstrapHttp.required(page,"id")),parent,BootstrapHttp.required(page,"title"),"page",marker);
    }
    @Override public String create(String parent, String title, String marker) {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("spaceId",spaceId); body.put("status","current"); body.put("title",title);
        if (parent != null) body.put("parentId",numeric(parent));
        body.put("body",Map.of("representation","storage","value","<p>"+marker+"</p>"));
        JsonNode created=http.post("/wiki/api/v2/pages"+(parent==null?"?root-level=true":""),body);
        try { return numeric(BootstrapHttp.required(created,"id")); }
        catch (DocumentProviderException unusableResponse) { throw BootstrapHttp.reconciliation(); }
    }
    private static String numeric(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,19}")) throw BootstrapHttp.failure();
        return value;
    }
}
