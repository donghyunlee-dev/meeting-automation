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
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.transfer.*;
import java.util.Map;
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
public final class ConfluencePageHierarchyAdapter implements ParticipantCreationProvider, ParticipantUpdatingProvider, DocumentTransferPort {
    private static final int PAGE_LIMIT = 100;
    private static final String TRANSFER_PARTICIPANT_TITLE_PREFIX = "Participant: ";
    private static final TransferCodec TRANSFER_CODEC = new TransferCodec();
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

    private final ProviderTransferSupport transfer = new ProviderTransferSupport() {
        @Override protected String fingerprint() { return codec.connectionFingerprint("confluence", baseUri.getHost() + "/" + configuredRootId); }
        @Override protected DocumentStructure structure() { return discoverStructure(configuredRootId); }
        @Override protected List<String> children(String root) { return transferChildren(root); }
        @Override protected TransferRecord read(String id) { return readTransferPage(id, pages); }
        @Override protected String create(String parent, TransferRecord record) { return createTransferPage(parent, record, pages); }
    };
    @Override public TransferManifest readManifest() { return transfer.readManifest(); }
    @Override public TransferRecord exportMeeting(String id) { return transfer.exportMeeting(id); }
    @Override public Participant importParticipant(TransferRecord r, String fingerprint) { return transfer.importParticipant(r, fingerprint); }
    @Override public MeetingDocumentRef importMeeting(TransferRecord r, Map<String,String> map, String fingerprint) { return transfer.importMeeting(r, map, fingerprint); }

    private List<String> transferChildren(String root) {
        List<String> children = new ArrayList<>(); Set<String> seen = new HashSet<>(); URI uri = initialChildrenUri(root);
        while (uri != null) {
            if (!seen.add(uri.toASCIIString())) throw transferInvalid();
            ResponseEnvelope response = get(uri); JsonNode body = parseBody(response); JsonNode results = body.get("results");
            if (results == null || !results.isArray()) throw transferInvalid();
            for (JsonNode page : results) {
                if (!page.isObject() || !"page".equals(transferText(page.get("type")))) throw transferInvalid();
                transferText(page.get("title")); String id = transferText(page.get("id")); if (!isNumericId(id)) throw transferInvalid(); children.add(id);
            }
            URI next = nextUri(body, response.linkHeader(), uri, response.statusCode());
            if (next != null && (!next.getPath().equals(uri.getPath()) || isBlank(next.getQuery()) || !next.getQuery().matches(".*(?:^|&)cursor=[^&]+(?:&.*)?"))) throw transferInvalid();
            uri = next;
        }
        return children;
    }
    private TransferRecord readTransferPage(String id, TransferPageCodec mapper) {
        if (!isNumericId(id)) throw transferInvalid();
        JsonNode page = parseBody(get(baseUri.resolve("/wiki/api/v2/pages/" + encodePathSegment(id) + "?body-format=storage")));
        if (!id.equals(transferText(page.get("id")))) throw transferInvalid();
        JsonNode version = page.path("version").path("number"); if (!version.isIntegralNumber() || version.longValue() <= 0) throw transferInvalid();
        List<TransferPageCodec.Block> blocks = storageBlocks(transferText(page.path("body").path("storage").get("value")));
        String title = transferText(page.get("title"));
        List<String> markers = blocks.stream().filter(block -> block.type().equals("code")).map(TransferPageCodec.Block::text).toList();
        TransferMarker marker = TRANSFER_CODEC.parseMarker(markers);
        if (marker.recordType().equals("participant") && title.startsWith(TRANSFER_PARTICIPANT_TITLE_PREFIX)) {
            title = title.substring(TRANSFER_PARTICIPANT_TITLE_PREFIX.length());
        }
        return mapper.decode(id, version.asString(), baseUri.resolve("/wiki/pages/" + id).toASCIIString(), title, blocks);
    }
    private String createTransferPage(String parent, TransferRecord record, TransferPageCodec mapper) {
        JsonNode root = parseBody(get(baseUri.resolve("/wiki/api/v2/pages/" + encodePathSegment(parent) + "?body-format=storage")));
        String space = transferText(root.get("spaceId")); StringBuilder storage = new StringBuilder();
        for (var block : mapper.encode(record)) {
            String tag = switch (block.type()) { case "heading_2" -> "h2"; case "bulleted_list_item" -> "li"; case "code" -> "pre"; default -> "p"; };
            if (tag.equals("li")) storage.append("<ul>");
            storage.append('<').append(tag).append('>').append(escapeHtml(block.text())).append("</").append(tag).append('>');
            if (tag.equals("li")) storage.append("</ul>");
        }
        try {
            String title = record.participant() == null ? record.title() : TRANSFER_PARTICIPANT_TITLE_PREFIX + record.participant().name();
            String payload = JSON_MAPPER.writeValueAsString(Map.of("spaceId", space, "status", "current", "title", title, "parentId", parent,
                    "body", Map.of("representation", "storage", "value", storage.toString())));
            String response = restClient.post().uri(baseUri.resolve("/wiki/api/v2/pages")).headers(headers -> {
                headers.setBasicAuth(accountEmail, authToken, StandardCharsets.UTF_8); headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            }).body(payload).exchange((request, result) -> { if (result.getStatusCode().isError()) throw transferInvalid(); return StreamUtils.copyToString(result.getBody(), StandardCharsets.UTF_8); });
            String id = transferText(JSON_MAPPER.readTree(response).get("id")); if (!isNumericId(id)) throw transferInvalid(); return id;
        } catch (Exception e) { throw transferInvalid(); }
    }
    private static List<TransferPageCodec.Block> storageBlocks(String storage) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder(); builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var document = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader("<body>" + storage + "</body>")));
            List<TransferPageCodec.Block> blocks = new ArrayList<>(); storageBlocks(document.getDocumentElement(), blocks); return blocks;
        } catch (Exception e) { throw transferInvalid(); }
    }
    private static void storageBlocks(org.w3c.dom.Node root, List<TransferPageCodec.Block> blocks) {
        var nodes = root.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE && node.getTextContent().isBlank()) continue;
            String type = switch (node.getNodeName()) { case "h2" -> "heading_2"; case "p" -> "paragraph"; case "li" -> "bulleted_list_item"; case "pre" -> "code"; default -> null; };
            if (node.getNodeName().equals("ul")) { storageBlocks(node, blocks); continue; }
            if (node.getNodeName().equals("p") && node instanceof org.w3c.dom.Element paragraph) {
                var inlineCode = paragraph.getElementsByTagName("code");
                if (inlineCode.getLength() == 1 && paragraph.getTextContent().equals(inlineCode.item(0).getTextContent())) {
                    blocks.add(new TransferPageCodec.Block("code", inlineCode.item(0).getTextContent()));
                    continue;
                }
            }
            if (node.getNodeName().equals("ac:structured-macro") && node instanceof org.w3c.dom.Element e && "code".equals(e.getAttribute("ac:name"))) {
                var bodies = e.getElementsByTagName("ac:plain-text-body"); if (bodies.getLength() != 1) throw transferInvalid(); blocks.add(new TransferPageCodec.Block("code", bodies.item(0).getTextContent())); continue;
            }
            if (type == null) throw transferInvalid(); blocks.add(new TransferPageCodec.Block(type, node.getTextContent()));
        }
    }
    private static String transferText(JsonNode node) { if (node == null || !(node.isString() || node.isNumber()) || node.asString().isBlank()) throw transferInvalid(); return node.asString(); }
    private static TransferCodec.ValidationException transferInvalid() { return new TransferCodec.ValidationException(TransferCodec.Code.INVALID_RECORD); }

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
                            "value", "<p>Email: " + escapeHtml(command.email()) + "</p><pre>" + escapeHtml(new TransferCodec().encodeMarker(
                                    ParticipantTransferMarkers.refreshed(new Participant("pending", command.name(), command.email()), null))) + "</pre>")));
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
        {
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
            TransferCodec codec = new TransferCodec();
            TransferMarker marker = codec.parseMarker(storageBlocks(bodyNode.stringValue()).stream().filter(b -> b.type().equals("code")).map(TransferPageCodec.Block::text).toList());
            ParticipantTransferMarkers.validateCurrent(command.existing(), marker);
            String updatedBody = command.email() == null ? bodyNode.stringValue() : replaceEmailLine(bodyNode.stringValue(), command.updatedEmail());
            TransferMarker updatedMarker = ParticipantTransferMarkers.refreshed(new Participant(participantId, command.updatedName(), command.updatedEmail()), marker);
            Matcher codeBlock = Pattern.compile("(?s)<pre(?:\\s[^>]*)?>.*?</pre>|<ac:structured-macro[^>]*>.*?</ac:structured-macro>").matcher(updatedBody);
            boolean replaced = false;
            while (codeBlock.find()) if (codeBlock.group().contains("meeting-automation")) {
                updatedBody = updatedBody.substring(0, codeBlock.start()) + "<pre>" + escapeHtml(codec.encodeMarker(updatedMarker)) + "</pre>" + updatedBody.substring(codeBlock.end()); replaced = true; break;
            }
            if (!replaced) throw transferInvalid();
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
            URI resolved = parsed.isAbsolute() ? parsed : rawNext.startsWith("?")
                    ? new URI(currentUri.getScheme(), currentUri.getAuthority(), currentUri.getPath(), parsed.getQuery(), null)
                    : currentUri.resolve(parsed);
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
                .replace("\"", "&quot;").replace("'", "&#39;").replace("\r", "&#13;");
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
