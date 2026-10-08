package com.meetingautomation.document.notion;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.CreateParticipantCommand;
import com.meetingautomation.document.ProviderHealth;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantCreationProvider;
import com.meetingautomation.document.ParticipantPageMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
public final class NotionPageHierarchyAdapter implements ParticipantCreationProvider {
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
            boolean authenticated = failure.statusCode() != 401 && failure.statusCode() != 403;
            return new ProviderHealth(true, failure.responseReceived() && authenticated, false);
        }
    }

    @Override
    public DocumentStructure discoverStructure(String rootId) {
        return discoverStructure(rootId, false, false);
    }

    @Override
    public DocumentStructure discoverParticipantStructure(String rootId) {
        return discoverStructure(rootId, true, false);
    }

    @Override
    public DocumentStructure discoverParticipantCreateStructure(String rootId) {
        return discoverStructure(rootId, false, true);
    }

    private DocumentStructure discoverStructure(
            String rootId, boolean participantListingOperation, boolean participantCreatePreflight) {
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
            if (participantListingOperation) {
                throw DocumentProviderException.participantListFailed(
                        isParticipantRetryable(failure.statusCode()), failure);
            }
            if (participantCreatePreflight) {
                throw DocumentProviderException.documentFailed(
                        isParticipantRetryable(failure.statusCode()), failure);
            }
            throw DocumentProviderException.documentFailed(isRetryable(failure.statusCode()), failure);
        }
    }

    @Override
    public List<Participant> listParticipants(String participantsPageId) {
        try {
            List<Participant> result = new ArrayList<>();
            for (ChildPage page : readAllChildren(participantsPageId, true)) {
                result.add(ParticipantPageMapper.map(page.id(), page.title(), readPageText(page.id())));
            }
            return List.copyOf(result);
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (NotionApiFailure failure) {
            throw DocumentProviderException.participantListFailed(isParticipantRetryable(failure.statusCode()), failure);
        }
    }

    @Override
    public Participant createParticipant(String participantsPageId, CreateParticipantCommand command) {
        if (isBlank(token) || isBlank(participantsPageId)) {
            throw DocumentProviderException.documentFailed(false);
        }
        ResponseEnvelope response;
        try {
            String requestBody = JSON_MAPPER.writeValueAsString(java.util.Map.of(
                    "parent", java.util.Map.of("page_id", participantsPageId),
                    "properties", java.util.Map.of("title", java.util.Map.of("title", java.util.List.of(
                            java.util.Map.of("text", java.util.Map.of("content", command.name()))))),
                    "children", java.util.List.of(java.util.Map.of(
                            "object", "block", "type", "paragraph",
                            "paragraph", java.util.Map.of("rich_text", java.util.List.of(
                                    java.util.Map.of("type", "text", "text",
                                            java.util.Map.of("content", "Email: " + command.email()))))))));
            response = restClient.post()
                    .uri("/v1/pages")
                    .headers(headers -> {
                        headers.setBearerAuth(token);
                        headers.set("Notion-Version", API_VERSION);
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    })
                    .body(requestBody)
                    .exchange((request, httpResponse) -> {
                        int status = httpResponse.getStatusCode().value();
                        if (HttpStatusCode.valueOf(status).isError()) {
                            throw new NotionApiFailure(true, status);
                        }
                        try {
                            return new ResponseEnvelope(status,
                                    StreamUtils.copyToString(httpResponse.getBody(), StandardCharsets.UTF_8));
                        } catch (IOException readFailure) {
                            throw new NotionApiFailure(true, status);
                        }
                    });
        } catch (NotionApiFailure failure) {
            throw DocumentProviderException.documentFailed(isCreateRetryable(failure.statusCode()), failure);
        } catch (RestClientException transportFailure) {
            throw DocumentProviderException.documentFailed(false, transportFailure);
        } catch (Exception invalidRequestBody) {
            throw DocumentProviderException.documentFailed(false, invalidRequestBody);
        }

        try {
            JsonNode responseBody = JSON_MAPPER.readTree(response.body());
            JsonNode id = responseBody == null ? null : responseBody.get("id");
            if (id == null || !id.isTextual() || isBlank(id.textValue())) {
                throw DocumentProviderException.documentFailed(false);
            }
            return new Participant(id.textValue(), command.name(), command.email());
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (Exception malformedResponse) {
            throw DocumentProviderException.documentFailed(false, malformedResponse);
        }
    }

    private String readPageText(String pageId) {
        List<String> lines = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        boolean hasMore;
        do {
            ResponseEnvelope envelope;
            String startCursor = cursor;
            try {
                envelope = restClient.get()
                        .uri(uriBuilder -> {
                            var uri = uriBuilder.path(CHILDREN_PATH);
                            if (startCursor != null) uri.queryParam("start_cursor", startCursor);
                            return uri.build(pageId);
                        })
                        .headers(headers -> {
                            headers.setBearerAuth(token);
                            headers.set("Notion-Version", API_VERSION);
                        })
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();
                            if (HttpStatusCode.valueOf(status).isError()) {
                                throw new NotionApiFailure(true, status);
                            }
                            try {
                                return new ResponseEnvelope(status,
                                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8));
                            } catch (IOException readFailure) {
                                throw new NotionApiFailure(true, status);
                            }
                        });
            } catch (NotionApiFailure failure) {
                throw failure;
            } catch (RestClientException transportFailure) {
                throw new NotionApiFailure(false, 0);
            }

            try {
                JsonNode response = JSON_MAPPER.readTree(envelope.body());
                JsonNode results = response == null ? null : response.get("results");
                if (results == null || !results.isArray()) {
                    throw new NotionApiFailure(true, envelope.statusCode());
                }
                for (JsonNode block : results) {
                    JsonNode type = block.get("type");
                    JsonNode content = type != null && type.isTextual() ? block.get(type.textValue()) : null;
                    JsonNode richText = content == null ? null : content.get("rich_text");
                    if (richText != null && richText.isArray()) {
                        StringBuilder blockText = new StringBuilder();
                        for (JsonNode text : richText) {
                            JsonNode plainText = text.get("plain_text");
                            if (plainText != null && plainText.isTextual()) {
                                blockText.append(plainText.textValue());
                            }
                        }
                        if (!blockText.isEmpty()) lines.add(blockText.toString());
                    }
                }
                JsonNode hasMoreNode = response.get("has_more");
                JsonNode nextCursor = response.get("next_cursor");
                if (hasMoreNode == null || !hasMoreNode.isBoolean()) {
                    throw new NotionApiFailure(true, envelope.statusCode());
                }
                hasMore = hasMoreNode.booleanValue();
                if (hasMore && (nextCursor == null || !nextCursor.isTextual())) {
                    throw new NotionApiFailure(true, envelope.statusCode());
                }
                cursor = hasMore ? nextCursor.textValue() : null;
                if (cursor != null && !seenCursors.add(cursor)) {
                    throw new NotionApiFailure(true, envelope.statusCode());
                }
            } catch (NotionApiFailure failure) {
                throw failure;
            } catch (Exception failure) {
                throw new NotionApiFailure(true, envelope.statusCode());
            }
        } while (hasMore);
        return String.join("\n", lines);
    }

    private List<ChildPage> readAllChildren(String rootId) {
        return readAllChildren(rootId, false);
    }

    private List<ChildPage> readAllChildren(String rootId, boolean strictParticipantPages) {
        List<ChildPage> pages = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
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
            pages.addAll(strictParticipantPages
                    ? participantChildPages(resultsNode, response.statusCode())
                    : childPages(resultsNode));
            boolean more = hasMoreNode.booleanValue();
            hasMore = more;
            JsonNode nextCursor = responseBody.get("next_cursor");
            if (hasMore && (nextCursor == null || !nextCursor.isTextual())) {
                throw new NotionApiFailure(true, response.statusCode());
            }
            cursor = hasMore ? nextCursor.textValue() : null;
            if (cursor != null && !seenCursors.add(cursor)) {
                throw new NotionApiFailure(true, response.statusCode());
            }
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

    private static List<ChildPage> participantChildPages(JsonNode results, int statusCode) {
        List<ChildPage> pages = new ArrayList<>();
        for (JsonNode block : results) {
            if (!block.isObject()) continue;
            JsonNode type = block.get("type");
            if (type == null || !type.isTextual() || !"child_page".equals(type.textValue())) continue;
            JsonNode id = block.get("id");
            JsonNode page = block.get("child_page");
            JsonNode title = page == null ? null : page.get("title");
            if (id == null || !id.isTextual() || isBlank(id.textValue())
                    || page == null || !page.isObject() || title == null || !title.isTextual()
                    || isBlank(title.textValue())) {
                throw new NotionApiFailure(true, statusCode);
            }
            pages.add(new ChildPage(id.textValue(), title.textValue()));
        }
        return pages;
    }

    private static List<String> childIdsNamed(List<ChildPage> children, String title) {
        return children.stream()
                .filter(child -> title.equals(child.title()))
                .map(ChildPage::id)
                .toList();
    }

    public boolean isConfigured() {
        return !isBlank(token) && !isBlank(configuredRootId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private static boolean isParticipantRetryable(int statusCode) {
        return statusCode == 0 || isRetryable(statusCode);
    }

    private static boolean isCreateRetryable(int statusCode) {
        return statusCode == 429;
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
