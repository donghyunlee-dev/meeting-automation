package com.meetingautomation.document.confluence;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.CreateParticipantCommand;
import com.meetingautomation.document.ProviderHealth;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantCreationProvider;
import com.meetingautomation.document.ParticipantUpdateCommand;
import com.meetingautomation.document.ParticipantUpdatingProvider;
import com.meetingautomation.document.ParticipantPageMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Confluence Cloud implementation of the document hierarchy and health slice. */
public final class ConfluencePageHierarchyAdapter implements ParticipantCreationProvider, ParticipantUpdatingProvider {
    private static final int PAGE_LIMIT = 100;
    private static final String DIRECT_CHILDREN_PATH = "/wiki/api/v2/pages/{id}/direct-children";
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel\\s*=\\s*\"?next\"?",
            Pattern.CASE_INSENSITIVE);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final URI baseUri;
    private final String accountEmail;
    private final String authToken;
    private final String configuredRootId;
    private final boolean configured;

    public ConfluencePageHierarchyAdapter(
            RestClient.Builder restClientBuilder,
            String baseUrl, String accountEmail, String authToken, String rootId) {
        this.baseUri = validBaseUri(baseUrl);
        this.accountEmail = accountEmail;
        this.authToken = authToken;
        this.configuredRootId = rootId;
        this.configured = baseUri != null && isValidEmail(accountEmail) && isNumericId(rootId)
                && !isBlank(authToken) && !isBlank(rootId);
        this.restClient = restClientBuilder.build();
    }

    @Override
    public ProviderHealth validateConnection() {
        if (!configured) {
            return new ProviderHealth(false, false, false);
        }
        try {
            readAllChildren(configuredRootId);
            return new ProviderHealth(true, true, true);
        } catch (ConfluenceApiFailure failure) {
            boolean reachable = failure.responseReceived() && !isAuthenticationFailure(failure.statusCode());
            return new ProviderHealth(true, reachable, false);
        }
    }

    public boolean isConfigured() {
        return configured;
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
        if (!configured || !isNumericId(rootId)) {
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
        } catch (ConfluenceApiFailure failure) {
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
        if (!configured) {
            throw DocumentProviderException.participantListFailed(false);
        }
        try {
            List<Participant> result = new ArrayList<>();
            for (ChildPage page : readAllChildren(participantsPageId, true)) {
                result.add(ParticipantPageMapper.map(page.id(), page.title(), readPageText(page.id())));
            }
            return List.copyOf(result);
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (ConfluenceApiFailure failure) {
            throw DocumentProviderException.participantListFailed(
                    isParticipantRetryable(failure.statusCode()), failure);
        }
    }

    @Override
    public Participant createParticipant(String participantsPageId, CreateParticipantCommand command) {
        if (!configured || !isNumericId(participantsPageId)) {
            throw DocumentProviderException.documentFailed(false);
        }

        String spaceId;
        try {
            URI parentUri = baseUri.resolve("/wiki/api/v2/pages/" + encodePathSegment(participantsPageId)
                    + "?body-format=storage");
            JsonNode parentPage = parseBody(get(parentUri));
            JsonNode spaceIdNode = parentPage.get("spaceId");
            if (spaceIdNode == null || (!spaceIdNode.isTextual() && !spaceIdNode.isNumber())
                    || isBlank(spaceIdNode.asText())) {
                throw DocumentProviderException.documentFailed(false);
            }
            spaceId = spaceIdNode.asText();
        } catch (ConfluenceApiFailure failure) {
            // This is a read-only preflight, so a transport failure is safe for clients to retry.
            throw DocumentProviderException.documentFailed(isParticipantRetryable(failure.statusCode()), failure);
        }

        ResponseEnvelope response;
        try {
            String requestBody = JSON_MAPPER.writeValueAsString(java.util.Map.of(
                    "spaceId", spaceId,
                    "status", "current",
                    "title", command.name(),
                    "parentId", participantsPageId,
                    "body", java.util.Map.of(
                            "representation", "storage",
                            "value", "<p>Email: " + escapeHtml(command.email()) + "</p>")));
            URI createUri = baseUri.resolve("/wiki/api/v2/pages");
            response = restClient.post()
                    .uri(createUri)
                    .headers(headers -> {
                        headers.setBasicAuth(accountEmail, authToken, StandardCharsets.UTF_8);
                        headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_JSON));
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    })
                    .body(requestBody)
                    .exchange((request, httpResponse) -> {
                        int status = httpResponse.getStatusCode().value();
                        if (HttpStatusCode.valueOf(status).isError()) {
                            throw new ConfluenceApiFailure(true, status);
                        }
                        try {
                            return new ResponseEnvelope(status,
                                    StreamUtils.copyToString(httpResponse.getBody(), StandardCharsets.UTF_8), null);
                        } catch (IOException readFailure) {
                            throw new ConfluenceApiFailure(true, status);
                        }
                    });
        } catch (ConfluenceApiFailure failure) {
            throw DocumentProviderException.documentFailed(isCreateRetryable(failure.statusCode()), failure);
        } catch (RestClientException transportFailure) {
            throw DocumentProviderException.documentFailed(false, transportFailure);
        } catch (Exception invalidRequestBody) {
            throw DocumentProviderException.documentFailed(false, invalidRequestBody);
        }

        try {
            JsonNode responseBody = JSON_MAPPER.readTree(response.body());
            JsonNode id = responseBody == null ? null : responseBody.get("id");
            String participantId = id != null && (id.isTextual() || id.isNumber()) ? id.asText() : null;
            if (!isNumericId(participantId)) {
                throw DocumentProviderException.documentFailed(false);
            }
            return new Participant(participantId, command.name(), command.email());
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (Exception malformedResponse) {
            throw DocumentProviderException.documentFailed(false, malformedResponse);
        }
    }

    @Override
    public Participant updateParticipant(String participantId, ParticipantUpdateCommand command) {
        if (!configured || !isNumericId(participantId)) {
            throw DocumentProviderException.documentFailed(false);
        }
        if (command.email() == null) {
            String uri = "/wiki/api/v2/pages/" + encodePathSegment(participantId) + "/title";
            mutate(uri, java.util.Map.of("status", "current", "title", command.updatedName()));
        } else {
            URI pageUri = baseUri.resolve("/wiki/api/v2/pages/" + encodePathSegment(participantId)
                    + "?body-format=storage");
            JsonNode page;
            try {
                page = parseBody(get(pageUri));
            } catch (ConfluenceApiFailure readFailure) {
                if (readFailure.statusCode() == 404) {
                    throw DocumentProviderException.participantNotFound();
                }
                throw DocumentProviderException.documentFailed(isParticipantRetryable(readFailure.statusCode()),
                        readFailure);
            }
            JsonNode version = page.path("version").path("number");
            JsonNode bodyNode = page.path("body").path("storage").path("value");
            JsonNode titleNode = page.get("title");
            JsonNode statusNode = page.get("status");
            JsonNode spaceNode = page.get("spaceId");
            JsonNode parentNode = page.get("parentId");
            if (!version.canConvertToInt() || !bodyNode.isTextual() || !titleNode.isTextual()
                    || !statusNode.isTextual() || spaceNode == null || parentNode == null) {
                throw DocumentProviderException.documentFailed(false);
            }
            String updatedBody = replaceEmailLine(bodyNode.textValue(), command.updatedEmail());
            java.util.Map<String, Object> requestBody = new java.util.LinkedHashMap<>();
            requestBody.put("id", participantId);
            requestBody.put("status", statusNode.textValue());
            requestBody.put("title", command.name() == null ? titleNode.textValue() : command.updatedName());
            requestBody.put("spaceId", spaceNode.isNumber() ? spaceNode.numberValue() : spaceNode.asText());
            requestBody.put("parentId", parentNode.isNumber() ? parentNode.numberValue() : parentNode.asText());
            requestBody.put("body", java.util.Map.of("representation", "storage", "value", updatedBody));
            requestBody.put("version", java.util.Map.of("number", version.intValue() + 1));
            mutate("/wiki/api/v2/pages/" + encodePathSegment(participantId), requestBody);
        }
        return new Participant(participantId, command.updatedName(), command.updatedEmail());
    }

    private void mutate(String path, Object body) {
        try {
            String requestBody = JSON_MAPPER.writeValueAsString(body);
            restClient.put()
                    .uri(baseUri.resolve(path))
                    .headers(headers -> {
                        headers.setBasicAuth(accountEmail, authToken, StandardCharsets.UTF_8);
                        headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_JSON));
                        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    })
                    .body(requestBody)
                    .exchange((request, response) -> {
                        if (HttpStatusCode.valueOf(response.getStatusCode().value()).isError()) {
                            throw new ConfluenceApiFailure(true, response.getStatusCode().value());
                        }
                        return null;
                    });
        } catch (Exception mutationFailure) {
            if (mutationFailure instanceof ConfluenceApiFailure providerFailure
                    && providerFailure.statusCode() == 404) {
                throw DocumentProviderException.participantNotFound();
            }
            if (mutationFailure instanceof DocumentProviderException providerFailure
                    && "PARTICIPANT_NOT_FOUND".equals(providerFailure.code())) {
                throw providerFailure;
            }
            // Any failure after a mutation request begins has an unknown outcome; never advise blind retries.
            throw DocumentProviderException.documentFailed(false, mutationFailure);
        }
    }

    private static String replaceEmailLine(String body, String email) {
        Matcher matcher = Pattern.compile("(?i)(Email:\\s*)([^<\\r\\n]*?)(\\s*(?:</p>|<br\\s*/?>|\\r?\\n|$))")
                .matcher(body);
        if (!matcher.find()) {
            throw DocumentProviderException.documentFailed(false);
        }
        String replacement = matcher.group(1) + escapeHtml(email) + matcher.group(3);
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String readPageText(String pageId) {
        URI pageUri = baseUri.resolve("/wiki/api/v2/pages/" + encodePathSegment(pageId) + "?body-format=storage");
        ResponseEnvelope response = get(pageUri);
        JsonNode body = parseBody(response);
        JsonNode storage = body.path("body").path("storage").path("value");
        if (!storage.isTextual()) {
            throw new ConfluenceApiFailure(true, response.statusCode());
        }
        String text = storage.textValue().replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n").replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return text;
    }

    private List<ChildPage> readAllChildren(String rootId) {
        return readAllChildren(rootId, false);
    }

    private List<ChildPage> readAllChildren(String rootId, boolean strictParticipantPages) {
        List<ChildPage> pages = new ArrayList<>();
        URI currentUri = initialChildrenUri(rootId);
        Set<String> requestedUris = new HashSet<>();
        requestedUris.add(currentUri.toASCIIString());

        while (currentUri != null) {
            ResponseEnvelope response = get(currentUri);
            JsonNode responseBody = parseBody(response);
            JsonNode results = responseBody.get("results");
            if (results == null || !results.isArray()) {
                throw new ConfluenceApiFailure(true, response.statusCode());
            }
            pages.addAll(strictParticipantPages
                    ? participantPageChildren(results, response.statusCode())
                    : pageChildren(results));

            URI nextUri = nextUri(responseBody, response.linkHeader(), currentUri, response.statusCode());
            if (nextUri == null) {
                currentUri = null;
            } else if (!requestedUris.add(nextUri.toASCIIString())) {
                throw new ConfluenceApiFailure(true, response.statusCode());
            } else {
                currentUri = nextUri;
            }
        }
        return List.copyOf(pages);
    }

    private ResponseEnvelope get(URI uri) {
        try {
            return restClient.get()
                    .uri(uri)
                    .headers(headers -> {
                        headers.setBasicAuth(accountEmail, authToken, StandardCharsets.UTF_8);
                        headers.setAccept(List.of(org.springframework.http.MediaType.APPLICATION_JSON));
                    })
                    .exchange((request, response) -> {
                        int statusCode = response.getStatusCode().value();
                        if (HttpStatusCode.valueOf(statusCode).isError()) {
                            throw new ConfluenceApiFailure(true, statusCode);
                        }
                        try {
                            return new ResponseEnvelope(statusCode,
                                    StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8),
                                    response.getHeaders().getFirst(HttpHeaders.LINK));
                        } catch (IOException responseReadFailure) {
                            throw new ConfluenceApiFailure(true, statusCode);
                        }
                    });
        } catch (ConfluenceApiFailure failure) {
            throw failure;
        } catch (RestClientException transportFailure) {
            throw new ConfluenceApiFailure(false, 0);
        }
    }

    private static JsonNode parseBody(ResponseEnvelope response) {
        try {
            JsonNode body = JSON_MAPPER.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw new ConfluenceApiFailure(true, response.statusCode());
            }
            return body;
        } catch (ConfluenceApiFailure failure) {
            throw failure;
        } catch (Exception malformedBody) {
            throw new ConfluenceApiFailure(true, response.statusCode());
        }
    }

    private URI initialChildrenUri(String rootId) {
        try {
            return URI.create(baseUri.toASCIIString() + DIRECT_CHILDREN_PATH.replace("{id}", encodePathSegment(rootId))
                    + "?limit=" + PAGE_LIMIT);
        } catch (IllegalArgumentException invalidRootId) {
            throw new ConfluenceApiFailure(false, 0);
        }
    }

    private URI nextUri(JsonNode body, String linkHeader, URI currentUri, int statusCode) {
        JsonNode links = body.get("_links");
        JsonNode next = links == null ? null : links.get("next");
        String rawNext;
        if (next != null && !next.isNull()) {
            if (!next.isTextual() || isBlank(next.textValue())) {
                throw new ConfluenceApiFailure(true, statusCode);
            }
            rawNext = next.textValue();
        } else {
            rawNext = nextLink(linkHeader);
        }
        if (rawNext == null) {
            return null;
        }

        try {
            URI parsed = new URI(rawNext);
            URI resolved = parsed.isAbsolute() ? parsed : currentUri.resolve(parsed);
            if (!sameOrigin(baseUri, resolved)) {
                throw new ConfluenceApiFailure(true, statusCode);
            }
            return resolved;
        } catch (URISyntaxException | IllegalArgumentException invalidNextLink) {
            throw new ConfluenceApiFailure(true, statusCode);
        }
    }

    private static String nextLink(String linkHeader) {
        if (isBlank(linkHeader)) {
            return null;
        }
        Matcher matcher = NEXT_LINK.matcher(linkHeader);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<ChildPage> pageChildren(JsonNode results) {
        List<ChildPage> pages = new ArrayList<>();
        for (JsonNode result : results) {
            if (!result.isObject()) {
                continue;
            }
            JsonNode type = result.get("type");
            JsonNode id = result.get("id");
            JsonNode title = result.get("title");
            if (type != null && type.isTextual() && "page".equals(type.textValue())
                    && id != null && id.isTextual() && title != null && title.isTextual()) {
                pages.add(new ChildPage(id.textValue(), title.textValue()));
            }
        }
        return pages;
    }

    private static List<ChildPage> participantPageChildren(JsonNode results, int statusCode) {
        List<ChildPage> pages = new ArrayList<>();
        for (JsonNode result : results) {
            if (!result.isObject()) continue;
            JsonNode type = result.get("type");
            if (type == null || !type.isTextual() || !"page".equals(type.textValue())) continue;
            JsonNode id = result.get("id");
            JsonNode title = result.get("title");
            if (id == null || !id.isTextual() || isBlank(id.textValue())
                    || title == null || !title.isTextual() || isBlank(title.textValue())) {
                throw new ConfluenceApiFailure(true, statusCode);
            }
            pages.add(new ChildPage(id.textValue(), title.textValue()));
        }
        return pages;
    }

    private static List<String> childIdsNamed(List<ChildPage> children, String title) {
        return children.stream().filter(child -> title.equals(child.title())).map(ChildPage::id).toList();
    }

    private static URI validBaseUri(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || isBlank(uri.getHost())
                    || !uri.getHost().toLowerCase(Locale.ROOT).endsWith(".atlassian.net")
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(isBlank(uri.getPath()) || "/".equals(uri.getPath()))) {
                return null;
            }
            return new URI("https", null, uri.getHost(), uri.getPort(), "", null, null);
        } catch (URISyntaxException invalidBaseUrl) {
            return null;
        }
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private static boolean isNumericId(String value) {
        return value != null && value.matches("[0-9]+");
    }

    private static boolean sameOrigin(URI expected, URI candidate) {
        return candidate.isAbsolute() && !isBlank(candidate.getHost())
                && candidate.getUserInfo() == null && candidate.getFragment() == null
                && expected.getScheme().equalsIgnoreCase(candidate.getScheme())
                && expected.getHost().toLowerCase(Locale.ROOT).equals(candidate.getHost().toLowerCase(Locale.ROOT))
                && effectivePort(expected) == effectivePort(candidate);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static boolean isAuthenticationFailure(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ChildPage(String id, String title) { }

    private record ResponseEnvelope(int statusCode, String body, String linkHeader) { }

    private static final class ConfluenceApiFailure extends RuntimeException {
        private final boolean responseReceived;
        private final int statusCode;

        private ConfluenceApiFailure(boolean responseReceived, int statusCode) {
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
