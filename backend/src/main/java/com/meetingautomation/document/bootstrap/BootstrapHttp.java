package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.settings.SettingsException;
import com.meetingautomation.document.DocumentProviderException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** No provider response, credential or request body is retained in a failure. */
final class BootstrapHttp {
    private static final Logger log = LoggerFactory.getLogger(BootstrapHttp.class);
    private final RestClient client;
    private final URI origin;
    BootstrapHttp(RestClient client, String origin) { this.client = client; this.origin = URI.create(origin); }
    JsonNode get(String path) { return exchange(path, null); }
    JsonNode post(String path, Object body) { return exchange(path, body); }
    JsonNode patch(String path, Object body) { return exchange(path, body, true); }
    private JsonNode exchange(String path, Object body) { return exchange(path, body, false); }
    private JsonNode exchange(String path, Object body, boolean patch) {
        try {
            URI target = origin.resolve(path);
            if (!origin.getScheme().equals(target.getScheme()) || !origin.getHost().equals(target.getHost())
                    || target.getPort() != origin.getPort() || target.getUserInfo() != null || target.getFragment() != null
                    || !(origin.getHost().equals("api.notion.com") ? target.getPath().startsWith("/v1/")
                        : target.getPath().startsWith("/wiki/api/v2/") || (body==null && target.getPath().equals("/wiki/rest/api/search"))))
                throw failure();
            var request = body == null ? client.get().uri(target)
                    : (patch ? client.patch() : client.post()).uri(target).contentType(MediaType.APPLICATION_JSON).body(body);
            JsonNode result = request.retrieve().onStatus(status -> !status.is2xxSuccessful(), (req,res) -> {
                var status=res.getStatusCode();
                log.error("Document bootstrap provider request failed: provider={}, method={}, endpoint={}, status={}",
                        provider(), method(body, patch), safeEndpoint(target.getPath()), status.value());
                throw DocumentProviderException.documentFailed(status.value()==429 || status.is5xxServerError());
            }).body(JsonNode.class);
            if (result == null || !result.isObject()) throw body!=null?reconciliation():failure();
            return result;
        } catch (SettingsException | DocumentProviderException safe) { throw safe; }
        catch (RuntimeException failure) {
            log.error("Document bootstrap provider request failed: provider={}, method={}, endpoint={}, cause={}",
                    provider(), method(body, patch), safeEndpoint(path), failure.getClass().getSimpleName());
            if (body!=null) throw reconciliation();
            throw DocumentProviderException.documentFailed(false);
        }
    }
    private String provider() { return "api.notion.com".equals(origin.getHost()) ? "notion" : "confluence"; }
    private static String method(Object body, boolean patch) { return body == null ? "GET" : patch ? "PATCH" : "POST"; }
    private static String safeEndpoint(String path) {
        if (path.startsWith("/v1/blocks/")) return "block-children";
        if (path.startsWith("/v1/pages")) return "pages";
        if (path.startsWith("/wiki/api/v2/pages/")) return "page";
        if (path.startsWith("/wiki/api/v2/pages")) return "pages";
        if (path.startsWith("/wiki/api/v2/spaces/")) return "space";
        if (path.startsWith("/wiki/rest/api/search")) return "search";
        return "provider-api";
    }
    static DocumentProviderException failure() { return DocumentProviderException.documentFailed(false); }
    static SettingsException reconciliation() { return new SettingsException("DOCUMENT_RECONCILIATION_REQUIRED",409); }
    static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || (!value.isTextual() && !value.isNumber()) || value.asText().isBlank()) throw failure();
        return value.asText();
    }
}
