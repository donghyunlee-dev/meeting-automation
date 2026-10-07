package com.meetingautomation.document.notion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentStructure;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

class NotionPageHierarchyAdapterTests extends com.meetingautomation.document.DocumentStructureProviderContractTest {
    private static final String TOKEN = "test-secret-token";
    private static final String ROOT_ID = "root-configured";
    private static final String URL = "https://api.notion.com/v1/blocks/root-configured/children";

    @Test
    void readsExactDirectChildPagesAndIgnoresDatabaseAndOtherBlocks() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(notionRequest(URL))
                .andRespond(jsonSuccess(children(
                        block("meetings-child", "child_page", "Meetings"),
                        block("participants-child", "child_page", "Participants"),
                        block("database-id", "child_database", "Meetings"),
                        block("lowercase-id", "child_page", "meetings"))));

        DocumentStructure structure = client.adapter().discoverStructure(ROOT_ID);

        assertEquals(new DocumentStructure(ROOT_ID, "meetings-child", "participants-child"), structure);
        client.server().verify();
    }

    @Test
    void forwardsOpaqueCursorUntilAllRootChildrenAreRead() {
        TestClient client = client(TOKEN, ROOT_ID);
        String cursor = "opaque cursor / page-one";
        client.server().expect(notionRequest(URL))
                .andRespond(jsonSuccess(childrenWithMore(cursor,
                        block("meetings-child", "child_page", "Meetings"))));
        client.server().expect(requestTo(URL + "?start_cursor=opaque%20cursor%20/%20page-one"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("start_cursor", "opaque%20cursor%20/%20page-one"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                        "Authorization", "Bearer " + TOKEN))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                        "Notion-Version", NotionPageHierarchyAdapter.API_VERSION))
                .andRespond(jsonSuccess(children(
                        block("participants-child", "child_page", "Participants"))));

        DocumentStructure structure = client.adapter().discoverStructure(ROOT_ID);

        assertEquals("meetings-child", structure.meetingsPageId());
        assertEquals("participants-child", structure.participantsPageId());
        client.server().verify();
    }

    @Test
    void emptyMissingDuplicateAndDatabaseOnlyStructuresAreRejectedWithoutCreation() {
        assertStructureFailure(emptyChildren());
        assertStructureFailure(children(block("meetings-child", "child_page", "Meetings")));
        assertStructureFailure(children(block("participants-child", "child_page", "Participants")));
        assertStructureFailure(children(
                block("meetings-a", "child_page", "Meetings"),
                block("meetings-b", "child_page", "Meetings"),
                block("participants-child", "child_page", "Participants")));
        assertStructureFailure(children(
                block("database-id", "child_database", "Meetings"),
                block("participants-child", "child_page", "Participants")));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 503})
    void providerHttpErrorsAreSafeAndHealthReflectsResponseReachability(int status) {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(notionRequest(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"provider-secret-token raw-response-marker\"}"));

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));
        assertEquals("DOCUMENT_FAILED", failure.code());
        assertEquals("DOCUMENT_FAILURE", failure.category());
        assertEquals(502, failure.statusCode());
        assertEquals(status == 429 || status >= 500, failure.retryable());
        assertFalse(failure.getMessage().contains(TOKEN));
        assertFalse(failure.getMessage().contains("raw-response-marker"));
        assertEquals(null, failure.getCause());
        client.server().verify();

        TestClient healthClient = client(TOKEN, ROOT_ID);
        healthClient.server().expect(notionRequest(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"raw-response-marker\"}"));
        assertEquals(new com.meetingautomation.document.ProviderHealth(true, true, false),
                healthClient.adapter().validateConnection());
        healthClient.server().verify();
    }

    @Test
    void networkFailureIsSafeAndReportsUnreachable() throws Exception {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(notionRequest(URL)).andRespond(request -> {
            throw new IOException("network-secret-marker");
        });

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));
        assertEquals("DOCUMENT_FAILED", failure.code());
        assertFalse(failure.getMessage().contains("network-secret-marker"));
        assertEquals(null, failure.getCause());
        client.server().verify();

        TestClient healthClient = client(TOKEN, ROOT_ID);
        healthClient.server().expect(notionRequest(URL)).andRespond(request -> {
            throw new IOException("network-secret-marker");
        });
        assertEquals(new com.meetingautomation.document.ProviderHealth(true, false, false),
                healthClient.adapter().validateConnection());
        healthClient.server().verify();
    }

    @Test
    void unconfiguredProviderDoesNotMakeRequests() {
        TestClient client = client(" ", ROOT_ID);
        assertEquals(new com.meetingautomation.document.ProviderHealth(false, false, false),
                client.adapter().validateConnection());
        client.server().verify();
    }

    @Test
    void missingChildStructureIsStillRootAccessible() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(notionRequest(URL)).andRespond(jsonSuccess(emptyChildren()));
        assertEquals(new com.meetingautomation.document.ProviderHealth(true, true, true),
                client.adapter().validateConnection());
        client.server().verify();
    }

    @Test
    void malformedSuccessfulResponseIsReachableButRootIsNotAccessible() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(notionRequest(URL)).andRespond(jsonSuccess("not-json"));

        assertEquals(new com.meetingautomation.document.ProviderHealth(true, true, false),
                client.adapter().validateConnection());
        client.server().verify();
    }

    @Override
    protected Supplier<com.meetingautomation.document.DocumentStructureProvider> structureProviderFactory() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(ExpectedCount.times(2), notionRequest(URL))
                .andRespond(jsonSuccess(children(
                        block("meetings-child", "child_page", "Meetings"),
                        block("participants-child", "child_page", "Participants"))));
        return client::adapter;
    }

    @Override
    protected Supplier<com.meetingautomation.document.DocumentStructureProvider> failingStructureProviderFactory() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(notionRequest(urlFor("missing-root-child")))
                .andRespond(jsonSuccess(children(block("meetings-child", "child_page", "Meetings"))));
        return client::adapter;
    }

    private static void assertStructureFailure(String responseBody) {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(notionRequest(URL)).andRespond(jsonSuccess(responseBody));

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));
        assertEquals("DOCUMENT_STRUCTURE_NOT_FOUND", failure.code());
        client.server().verify();
    }

    private static RequestMatcher notionRequest(String url) {
        return request -> {
            requestTo(url).match(request);
            method(HttpMethod.GET).match(request);
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                    "Authorization", "Bearer " + TOKEN).match(request);
            org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                    "Notion-Version", NotionPageHierarchyAdapter.API_VERSION).match(request);
        };
    }

    private static String urlFor(String rootId) {
        return "https://api.notion.com/v1/blocks/" + rootId + "/children";
    }

    private static TestClient client(String token, String rootId) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new NotionPageHierarchyAdapter(builder, token, rootId), server);
    }

    private static org.springframework.test.web.client.ResponseCreator jsonSuccess(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String children(String... blocks) {
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":[" + String.join(",", blocks) + "]}";
    }

    private static String emptyChildren() {
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":[]}";
    }

    private static String childrenWithMore(String cursor, String... blocks) {
        return "{\"has_more\":true,\"next_cursor\":\"" + cursor + "\",\"results\":["
                + String.join(",", blocks) + "]}";
    }

    private static String children(String first, String second, String third, String fourth) {
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":[" + first + "," + second + "," + third + "," + fourth + "]}";
    }

    private static String block(String id, String type, String title) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"child_page\":{\"title\":\"" + title + "\"}}";
    }

    private record TestClient(NotionPageHierarchyAdapter adapter, MockRestServiceServer server) {
    }
}
