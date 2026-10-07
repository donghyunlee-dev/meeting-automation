package com.meetingautomation.document.confluence;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.ProviderHealth;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Confluence Cloud implementation of the document hierarchy and health slice. */
@Component
public final class ConfluencePageHierarchyAdapter implements DocumentStructureProvider {
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
            @Value("${CONFLUENCE_BASE_URL:}") String baseUrl,
            @Value("${CONFLUENCE_ACCOUNT_EMAIL:}") String accountEmail,
            @Value("${CONFLUENCE_AUTH_TOKEN:}") String authToken,
            @Value("${DOCUMENT_ROOT_ID:}") String rootId) {
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

    @Override
    public DocumentStructure discoverStructure(String rootId) {
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
            throw DocumentProviderException.documentFailed(isRetryable(failure.statusCode()), failure);
        }
    }

    private List<ChildPage> readAllChildren(String rootId) {
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
            pages.addAll(pageChildren(results));

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
