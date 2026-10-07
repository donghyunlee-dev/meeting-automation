package com.meetingautomation.document.confluence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.ProviderHealth;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

class ConfluencePageHierarchyAdapterTests extends com.meetingautomation.document.DocumentStructureProviderContractTest {
    private static final String BASE_URL = "https://example.atlassian.net";
    private static final String EMAIL = "automation@example.com";
    private static final String TOKEN = "fake-api-token-marker";
    private static final String ROOT_ID = "12345";
    private static final String FIRST_URL = BASE_URL + "/wiki/api/v2/pages/" + ROOT_ID
            + "/direct-children?limit=100";
    private static final String AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString(
            (EMAIL + ":" + TOKEN).getBytes(StandardCharsets.UTF_8));

    @Test
    void mapsOnlyDirectPageChildrenAndUsesUtf8BasicAuthentication() {
        TestClient client = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        client.server().expect(confluenceRequest(FIRST_URL))
                .andRespond(jsonSuccess(children(
                        page("meetings-id", "Meetings"),
                        page("participants-id", "Participants"),
                        nonPage("database-id", "database", "Meetings"),
                        page("case-mismatch", "meetings"))));

        assertEquals(new DocumentStructure(ROOT_ID, "meetings-id", "participants-id"),
                client.adapter().discoverStructure(ROOT_ID));
        client.server().verify();
    }

    @Test
    void followsSameOriginOpaqueNextUrlAndLinkHeaderToCompletion() {
        TestClient client = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        String next = BASE_URL + "/wiki/api/v2/pages/" + ROOT_ID
                + "/direct-children?cursor=opaque%2Fvalue%3D%3D&limit=100";
        String nextFromHeader = BASE_URL + "/wiki/api/v2/pages/" + ROOT_ID
                + "/direct-children?cursor=header-cursor&limit=100";
        client.server().expect(confluenceRequest(FIRST_URL))
                .andRespond(jsonSuccess(childrenWithNext(page("meetings-id", "Meetings"), next)));
        client.server().expect(confluenceRequest(next))
                .andRespond(jsonSuccessWithLink(children(page("middle-page", "Other")),
                        "<" + nextFromHeader + ">; rel=\"next\""));
        client.server().expect(confluenceRequest(nextFromHeader))
                .andRespond(jsonSuccess(children(page("participants-id", "Participants"))));

        assertEquals(new DocumentStructure(ROOT_ID, "meetings-id", "participants-id"),
                client.adapter().discoverStructure(ROOT_ID));
        client.server().verify();
    }

    @Test
    void repeatedNextUrlFailsSafelyWithoutAThirdRequest() {
        TestClient client = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        String next = BASE_URL + "/wiki/api/v2/pages/" + ROOT_ID + "/direct-children?cursor=repeat-marker";
        client.server().expect(confluenceRequest(FIRST_URL))
                .andRespond(jsonSuccess(childrenWithNext(page("meetings-id", "Meetings"), next)));
        client.server().expect(confluenceRequest(next))
                .andRespond(jsonSuccess(childrenWithNext(page("participants-id", "Participants"), next)));

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));

        assertDocumentFailure(failure, false);
        assertFalse(failure.getMessage().contains("repeat-marker"));
        client.server().verify();
    }

    @Test
    void crossOriginNextUrlIsRejectedWithoutRequestingIt() {
        TestClient client = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        client.server().expect(confluenceRequest(FIRST_URL))
                .andRespond(jsonSuccess(childrenWithNext(page("meetings-id", "Meetings"),
                        "https://attacker.example/steal?token=marker")));

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));

        assertDocumentFailure(failure, false);
        assertFalse(failure.getMessage().contains("attacker.example"));
        client.server().verify();
    }

    @Test
    void emptyMissingAndDuplicatePageStructuresAreRejected() {
        assertStructureFailure(emptyChildren());
        assertStructureFailure(children(page("meetings-id", "Meetings")));
        assertStructureFailure(children(page("participants-id", "Participants")));
        assertStructureFailure(children(page("meetings-a", "Meetings"), page("meetings-b", "Meetings"),
                page("participants-id", "Participants")));
        assertStructureFailure(children(nonPage("database-id", "database", "Meetings"),
                page("participants-id", "Participants")));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 503})
    void httpFailuresAreNormalizedAndHealthReportsResponseReachability(int status) {
        TestClient client = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        client.server().expect(confluenceRequest(FIRST_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"secret\":\"" + TOKEN
                        + " raw-provider-body-marker\"}"));

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));
        assertDocumentFailure(failure, status == 429 || status >= 500);
        assertFalse(failure.getMessage().contains(TOKEN));
        assertFalse(failure.getMessage().contains("raw-provider-body-marker"));
        assertEquals(null, failure.getCause());
        client.server().verify();

        TestClient healthClient = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        healthClient.server().expect(confluenceRequest(FIRST_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"secret\":\"provider-marker\"}"));
        boolean authenticated = status != 401 && status != 403;
        assertEquals(new ProviderHealth(true, authenticated, false), healthClient.adapter().validateConnection());
        healthClient.server().verify();
    }

    @Test
    void networkFailureIsSafeAndReportsUnreachable() {
        TestClient client = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        client.server().expect(confluenceRequest(FIRST_URL)).andRespond(request -> {
            throw new IOException("transport-secret-marker");
        });

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));
        assertDocumentFailure(failure, false);
        assertFalse(failure.getMessage().contains("transport-secret-marker"));
        assertEquals(null, failure.getCause());
        client.server().verify();

        TestClient healthClient = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        healthClient.server().expect(confluenceRequest(FIRST_URL)).andRespond(request -> {
            throw new IOException("transport-secret-marker");
        });
        assertEquals(new ProviderHealth(true, false, false), healthClient.adapter().validateConnection());
        healthClient.server().verify();
    }

    @Test
    void invalidConfigurationIsNotConfiguredAndMakesNoRequest() {
        TestClient invalidBase = client("http://example.atlassian.net/path", EMAIL, TOKEN, ROOT_ID);
        assertEquals(new ProviderHealth(false, false, false), invalidBase.adapter().validateConnection());
        invalidBase.server().verify();

        TestClient invalidEmail = client(BASE_URL, "not-an-email", TOKEN, ROOT_ID);
        assertEquals(new ProviderHealth(false, false, false), invalidEmail.adapter().validateConnection());
        invalidEmail.server().verify();

        TestClient invalidRoot = client(BASE_URL, EMAIL, TOKEN, "root-123");
        assertEquals(new ProviderHealth(false, false, false), invalidRoot.adapter().validateConnection());
        invalidRoot.server().verify();
    }

    @Override
    protected String contractRootId() {
        return "12345";
    }

    @Override
    protected String missingContractRootId() {
        return "98765";
    }

    @Override
    protected DocumentStructure expectedContractStructure() {
        return new DocumentStructure(contractRootId(), "meetings-child", "participants-child");
    }

    @Override
    protected Supplier<DocumentStructureProvider> structureProviderFactory() {
        String contractRoot = contractRootId();
        TestClient client = client(BASE_URL, EMAIL, TOKEN, contractRoot);
        client.server().expect(ExpectedCount.times(2), confluenceRequest(childrenUrl(contractRoot)))
                .andRespond(jsonSuccess(children(page("meetings-child", "Meetings"),
                        page("participants-child", "Participants"))));
        return client::adapter;
    }

    @Override
    protected Supplier<DocumentStructureProvider> failingStructureProviderFactory() {
        TestClient client = client(BASE_URL, EMAIL, TOKEN, contractRootId());
        client.server().expect(confluenceRequest(childrenUrl(missingContractRootId())))
                .andRespond(jsonSuccess(children(page("meetings-id", "Meetings"))));
        return client::adapter;
    }

    private static void assertStructureFailure(String body) {
        TestClient client = client(BASE_URL, EMAIL, TOKEN, ROOT_ID);
        client.server().expect(confluenceRequest(FIRST_URL)).andRespond(jsonSuccess(body));
        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));
        assertEquals("DOCUMENT_STRUCTURE_NOT_FOUND", failure.code());
        assertEquals(422, failure.statusCode());
        assertFalse(failure.retryable());
        client.server().verify();
    }

    private static void assertDocumentFailure(DocumentProviderException failure, boolean retryable) {
        assertEquals("DOCUMENT_FAILED", failure.code());
        assertEquals("DOCUMENT_FAILURE", failure.category());
        assertEquals(502, failure.statusCode());
        assertEquals(retryable, failure.retryable());
    }

    private static RequestMatcher confluenceRequest(String url) {
        return request -> {
            requestTo(url).match(request);
            method(HttpMethod.GET).match(request);
            header(HttpHeaders.AUTHORIZATION, AUTHORIZATION).match(request);
            header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).match(request);
        };
    }

    private static TestClient client(String baseUrl, String email, String token, String rootId) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new ConfluencePageHierarchyAdapter(builder, baseUrl, email, token, rootId), server);
    }

    private static String childrenUrl(String rootId) {
        return BASE_URL + "/wiki/api/v2/pages/" + rootId + "/direct-children?limit=100";
    }

    private static org.springframework.test.web.client.ResponseCreator jsonSuccess(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static org.springframework.test.web.client.ResponseCreator jsonSuccessWithLink(String body, String link) {
        return request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), HttpStatusCode.valueOf(200));
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            if (link != null) {
                response.getHeaders().set(HttpHeaders.LINK, link);
            }
            return response;
        };
    }

    private static String children(String... results) {
        return "{\"results\":[" + String.join(",", results) + "],\"_links\":{}}";
    }

    private static String childrenWithNext(String result, String next) {
        return "{\"results\":[" + result + "],\"_links\":{\"next\":\"" + next + "\"}}";
    }

    private static String emptyChildren() {
        return children();
    }

    private static String page(String id, String title) {
        return "{\"id\":\"" + id + "\",\"type\":\"page\",\"title\":\"" + title + "\"}";
    }

    private static String nonPage(String id, String type, String title) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"title\":\"" + title + "\"}";
    }

    private record TestClient(ConfluencePageHierarchyAdapter adapter, MockRestServiceServer server) {
    }
}
