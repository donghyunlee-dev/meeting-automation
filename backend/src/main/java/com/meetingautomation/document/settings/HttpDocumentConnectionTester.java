package com.meetingautomation.document.settings;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.notion.NotionPageHierarchyAdapter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

@Component
public final class HttpDocumentConnectionTester implements DocumentConnectionTester {
    private final RestClient.Builder builder;
    public HttpDocumentConnectionTester(RestClient.Builder builder) { this.builder = builder; }
    @Override public void verify(GlobalDocumentSettings.Draft draft) {
        try {
            if ("NOTION".equals(draft.provider())) {
                RestClient client = builder.clone().baseUrl("https://api.notion.com")
                    .defaultHeader("Authorization", "Bearer " + draft.credentials().get("token"))
                    .defaultHeader("Notion-Version", NotionPageHierarchyAdapter.API_VERSION).build();
                JsonNode user = read(client, "/v1/users/me");
                if (user == null || !"bot".equals(user.path("type").asText())) throw DocumentProviderException.documentFailed(false);
                JsonNode parent = read(client, "/v1/pages/{id}", draft.location().get("parentPageId"));
                if (parent == null || !"page".equals(parent.path("object").asText()) || parent.path("archived").asBoolean()
                        || parent.path("in_trash").asBoolean()) throw SettingsException.invalid();
            } else {
                RestClient client = builder.clone().baseUrl(draft.location().get("baseUrl"))
                    .defaultHeaders(headers -> headers.setBasicAuth(draft.credentials().get("accountEmail"),
                            draft.credentials().get("apiToken"))).build();
                JsonNode space = read(client, "/wiki/api/v2/spaces/{id}", draft.location().get("spaceId"));
                if (space == null || !draft.location().get("spaceId").equals(space.path("id").asText())) throw SettingsException.invalid();
                String parentId = draft.location().get("parentPageId");
                if (parentId != null) {
                    JsonNode parent = read(client, "/wiki/api/v2/pages/{id}", parentId);
                    if (parent == null || !draft.location().get("spaceId").equals(parent.path("spaceId").asText())
                            || !"current".equals(parent.path("status").asText())) throw SettingsException.invalid();
                }
            }
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) throw SettingsException.invalid();
            throw DocumentProviderException.documentFailed(failure.getStatusCode().value() == 429 || failure.getStatusCode().is5xxServerError());
        } catch (SettingsException | DocumentProviderException safe) { throw safe; }
        catch (RuntimeException ignored) { throw DocumentProviderException.documentFailed(true); }
    }
    private static JsonNode read(RestClient client, String path, Object... variables) {
        return client.get().uri(path, variables).retrieve()
            .onStatus(status -> status.is3xxRedirection(), (request, response) -> { throw DocumentProviderException.documentFailed(false); })
            .body(JsonNode.class);
    }
}
