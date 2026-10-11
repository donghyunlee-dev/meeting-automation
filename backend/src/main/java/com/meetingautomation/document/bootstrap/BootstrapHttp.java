package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.settings.SettingsException;
import com.meetingautomation.document.DocumentProviderException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** No provider response, credential or request body is retained in a failure. */
final class BootstrapHttp {
    private final RestClient client;
    private final URI origin;
    BootstrapHttp(RestClient client, String origin) { this.client = client; this.origin = URI.create(origin); }
    JsonNode get(String path) { return exchange(path, null); }
    JsonNode post(String path, Object body) { return exchange(path, body); }
    private JsonNode exchange(String path, Object body) {
        try {
            URI target = origin.resolve(path);
            if (!origin.getScheme().equals(target.getScheme()) || !origin.getHost().equals(target.getHost())
                    || target.getPort() != origin.getPort() || target.getUserInfo() != null || target.getFragment() != null
                    || !(origin.getHost().equals("api.notion.com") ? target.getPath().startsWith("/v1/")
                        : target.getPath().startsWith("/wiki/api/v2/") || (body==null && target.getPath().equals("/wiki/rest/api/search"))))
                throw failure();
            var request = body == null ? client.get().uri(target) : client.post().uri(target).contentType(MediaType.APPLICATION_JSON).body(body);
            JsonNode result = request.retrieve().onStatus(status -> !status.is2xxSuccessful(), (req,res) -> {
                var status=res.getStatusCode();
                throw DocumentProviderException.documentFailed(status.value()==429 || status.is5xxServerError());
            }).body(JsonNode.class);
            if (result == null || !result.isObject()) throw body!=null?reconciliation():failure();
            return result;
        } catch (SettingsException | DocumentProviderException safe) { throw safe; }
        catch (RuntimeException ignored) {
            if (body!=null) throw reconciliation();
            throw DocumentProviderException.documentFailed(false);
        }
    }
    static DocumentProviderException failure() { return DocumentProviderException.documentFailed(false); }
    static SettingsException reconciliation() { return new SettingsException("DOCUMENT_RECONCILIATION_REQUIRED",409); }
    static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || (!value.isTextual() && !value.isNumber()) || value.asText().isBlank()) throw failure();
        return value.asText();
    }
}
