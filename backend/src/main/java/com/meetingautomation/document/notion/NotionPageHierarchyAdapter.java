package com.meetingautomation.document.notion;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.ProviderHealth;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Notion implementation for the root hierarchy and connection health slice. */
@Component
public final class NotionPageHierarchyAdapter implements DocumentStructureProvider {
    public static final String API_VERSION = "2026-03-11";
    private static final String API_BASE_URL = "https://api.notion.com";
    private static final String CHILDREN_PATH = "/v1/blocks/{block_id}/children";
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final String token;
    private final String configuredRootId;

    public NotionPageHierarchyAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${NOTION_TOKEN:}") String token,
            @Value("${DOCUMENT_ROOT_ID:}") String configuredRootId) {
        this.token = token;
        this.configuredRootId = configuredRootId;
        this.restClient = restClientBuilder.baseUrl(API_BASE_URL).build();
    }

    @Override
    public ProviderHealth validateConnection() {
        if (!isConfigured()) {
            return new ProviderHealth(false, false, false);
        }
        try {
            readAllChildren(configuredRootId);
            return new ProviderHealth(true, true, true);
        } catch (NotionApiFailure failure) {
            return new ProviderHealth(true, failure.responseReceived(), false);
        }
    }

    @Override
    public DocumentStructure discoverStructure(String rootId) {
        if (isBlank(token) || isBlank(rootId)) {
            throw DocumentProviderException.documentFailed(false);
        }

        try {
            List<ChildPage> children = readAllChildren(rootId);
            List<String> meetings = childIdsNamed(children, "Meetings");
            List<String> participants = childIdsNamed(children, "Participants");
            if (meetings.size() != 1 || participants.size() != 1) {
                throw DocumentProviderException.structureNotFound();
            }
            return new DocumentStructure(rootId, meetings.getFirst(), participants.getFirst());
        } catch (NotionApiFailure failure) {
            throw DocumentProviderException.documentFailed(isRetryable(failure.statusCode()), failure);
        }
    }

    private List<ChildPage> readAllChildren(String rootId) {
        List<ChildPage> pages = new ArrayList<>();
        String cursor = null;
        boolean hasMore;
        do {
            String startCursor = cursor;
            ResponseEnvelope response;
            try {
                response = restClient.get()
                        .uri(uriBuilder -> {
                            var uri = uriBuilder.path(CHILDREN_PATH);
                            if (startCursor != null) {
                                uri.queryParam("start_cursor", startCursor);
                            }
                            return uri.build(rootId);
                        })
                        .headers(headers -> {
                            headers.setBearerAuth(token);
                            headers.set("Notion-Version", API_VERSION);
                        })
                        .exchange((request, httpResponse) -> {
                            int statusCode = httpResponse.getStatusCode().value();
                            if (HttpStatusCode.valueOf(statusCode).isError()) {
                                throw new NotionApiFailure(true, statusCode);
                            }
                            try {
                                return new ResponseEnvelope(statusCode,
                                        StreamUtils.copyToString(httpResponse.getBody(), StandardCharsets.UTF_8));
                            } catch (IOException responseReadFailure) {
                                throw new NotionApiFailure(true, statusCode);
                            }
                        })
                        ;
            } catch (NotionApiFailure failure) {
                throw failure;
            } catch (RestClientException failure) {
                throw new NotionApiFailure(false, 0);
            }

            JsonNode responseBody;
            try {
                responseBody = JSON_MAPPER.readTree(response.body());
            } catch (Exception malformedBody) {
                throw new NotionApiFailure(true, response.statusCode());
            }

            JsonNode resultsNode = responseBody == null ? null : responseBody.get("results");
            JsonNode hasMoreNode = responseBody == null ? null : responseBody.get("has_more");
            if (resultsNode == null || !resultsNode.isArray() || hasMoreNode == null || !hasMoreNode.isBoolean()) {
                throw new NotionApiFailure(true, response.statusCode());
            }
            pages.addAll(childPages(resultsNode));
            boolean more = hasMoreNode.booleanValue();
            hasMore = more;
            JsonNode nextCursor = responseBody.get("next_cursor");
            if (hasMore && (nextCursor == null || !nextCursor.isTextual())) {
                throw new NotionApiFailure(true, response.statusCode());
            }
            cursor = hasMore ? nextCursor.textValue() : null;
        } while (hasMore);
        return List.copyOf(pages);
    }

    private static List<ChildPage> childPages(JsonNode results) {
        List<ChildPage> pages = new ArrayList<>();
        for (JsonNode block : results) {
            JsonNode type = block.get("type");
            if (!block.isObject() || type == null || !type.isTextual() || !"child_page".equals(type.textValue())) {
                continue;
            }
            JsonNode id = block.get("id");
            JsonNode page = block.get("child_page");
            JsonNode title = page == null ? null : page.get("title");
            if (id != null && id.isTextual() && page != null && page.isObject()
                    && title != null && title.isTextual()) {
                pages.add(new ChildPage(id.textValue(), title.textValue()));
            }
        }
        return pages;
    }

    private static List<String> childIdsNamed(List<ChildPage> children, String title) {
        return children.stream()
                .filter(child -> title.equals(child.title()))
                .map(ChildPage::id)
                .toList();
    }

    private boolean isConfigured() {
        return !isBlank(token) && !isBlank(configuredRootId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private record ChildPage(String id, String title) {
    }

    private record ResponseEnvelope(int statusCode, String body) {
    }

    private static final class NotionApiFailure extends RuntimeException {
        private final boolean responseReceived;
        private final int statusCode;

        private NotionApiFailure(boolean responseReceived, int statusCode) {
            this.responseReceived = responseReceived;
            this.statusCode = statusCode;
        }

        private boolean responseReceived() {
            return responseReceived;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
