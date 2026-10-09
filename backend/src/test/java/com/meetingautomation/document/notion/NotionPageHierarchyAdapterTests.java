package com.meetingautomation.document.notion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.CreateParticipantCommand;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantUpdateCommand;
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
    void createsParticipantPageWithTitleAndEmailContent() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                        "Authorization", "Bearer " + TOKEN))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header(
                        "Notion-Version", NotionPageHierarchyAdapter.API_VERSION))
                .andExpect(content().json("""
                        {"parent":{"page_id":"participants-page"},
                         "properties":{"title":{"title":[{"text":{"content":"Ada Lovelace"}}]}},
                         "children":[{"object":"block","type":"paragraph","paragraph":{"rich_text":[
                           {"type":"text","text":{"content":"Email: ada@example.test"}}]}}]}
                        """))
                .andRespond(jsonSuccess("{\"id\":\"new-notion-page\"}"));

        assertEquals(new Participant("new-notion-page", "Ada Lovelace", "ada@example.test"),
                client.adapter().createParticipant("participants-page",
                        new CreateParticipantCommand("Ada Lovelace", "ada@example.test")));
        client.server().verify();
    }

    @Test
    void updatesOnlyRequestedNotionParticipantFieldsAndPreservesOtherBlockText() {
        TestClient client = client(TOKEN, ROOT_ID);
        String childrenUrl = "https://api.notion.com/v1/blocks/person-page/children";
        client.server().expect(notionRequest(childrenUrl)).andRespond(jsonSuccess(
                "{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"id\":\"email-block\",\"type\":\"paragraph\",\"paragraph\":{"
                        + "\"rich_text\":["
                        + "{\"type\":\"text\",\"text\":{\"content\":\"Email: old@\"},"
                        + "\"annotations\":{\"bold\":true}},"
                        + "{\"type\":\"text\",\"text\":{\"content\":\"example.test\\nRole: Engineer\"},"
                        + "\"annotations\":{\"italic\":true}}]}}]}"));
        client.server().expect(requestTo("https://api.notion.com/v1/blocks/email-block"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {"paragraph":{"rich_text":[
                          {"type":"text","text":{"content":"Email: "}},
                          {"type":"text","text":{"content":"new@example.test"},"annotations":{"bold":true}},
                          {"type":"text","text":{"content":"\\nRole: Engineer"}}
                        ]}}
                        """))
                .andRespond(jsonSuccess("{\"id\":\"email-block\"}"));

        assertEquals(new Participant("person-page", "Ada Lovelace", "new@example.test"),
                client.adapter().updateParticipant("person-page", new ParticipantUpdateCommand(
                        null, "new@example.test", new Participant("person-page", "Ada Lovelace", "old@example.test"))));
        client.server().verify();
    }

    @Test
    void updatesNotionTitleWithoutRewritingParticipantBody() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(requestTo("https://api.notion.com/v1/pages/person-page"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {"properties":{"title":{"title":[{"text":{"content":"Grace Hopper"}}]}}}
                        """))
                .andRespond(jsonSuccess("{\"id\":\"person-page\"}"));

        assertEquals(new Participant("person-page", "Grace Hopper", "ada@example.test"),
                client.adapter().updateParticipant("person-page", new ParticipantUpdateCommand(
                        "Grace Hopper", null, new Participant("person-page", "Ada Lovelace", "ada@example.test"))));
        client.server().verify();
    }

    @Test
    void missingNotionTargetOnPageMutationReturnsParticipantNotFound() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(requestTo("https://api.notion.com/v1/pages/person-page"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(HttpStatusCode.valueOf(404)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"notion-page-secret-marker\"}"));

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().updateParticipant("person-page", new ParticipantUpdateCommand(
                        "Grace Hopper", null,
                        new Participant("person-page", "Ada Lovelace", "ada@example.test"))));

        assertEquals("PARTICIPANT_NOT_FOUND", failure.code());
        assertEquals(404, failure.statusCode());
        assertFalse(failure.retryable());
        assertFalse(failure.getMessage().contains("notion-page-secret-marker"));
        assertEquals(null, failure.getCause());
        client.server().verify();
    }

    @Test
    void notionRateLimitCreateFailureIsRetryableAndSanitized() {
        TestClient client = client(TOKEN, ROOT_ID);
        client.server().expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withStatus(HttpStatusCode.valueOf(429))
                        .contentType(MediaType.APPLICATION_JSON).body("notion-secret-marker"));

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().createParticipant("participants-page",
                        new CreateParticipantCommand("Ada", "ada@example.test")));

        assertEquals("DOCUMENT_FAILED", failure.code());
        assertEquals(502, failure.statusCode());
        assertEquals(true, failure.retryable());
        assertFalse(failure.getMessage().contains("notion-secret-marker"));
        assertEquals(null, failure.getCause());
        client.server().verify();
    }

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
    void repeatedOpaqueCursorFailsSafelyInsteadOfLooping() {
        TestClient client = client(TOKEN, ROOT_ID);
        String cursor = "loop-cursor-marker";
        client.server().expect(notionRequest(URL))
                .andRespond(jsonSuccess(childrenWithMore(cursor,
                        block("meetings-child", "child_page", "Meetings"))));
        client.server().expect(notionRequest(URL + "?start_cursor=" + cursor))
                .andExpect(queryParam("start_cursor", cursor))
                .andRespond(jsonSuccess(childrenWithMore(cursor,
                        block("participants-child", "child_page", "Participants"))));

        DocumentProviderException failure = assertThrows(DocumentProviderException.class,
                () -> client.adapter().discoverStructure(ROOT_ID));

        assertEquals("DOCUMENT_FAILED", failure.code());
        assertEquals("DOCUMENT_FAILURE", failure.category());
        assertEquals(502, failure.statusCode());
        assertFalse(failure.retryable());
        assertFalse(failure.getMessage().contains(cursor));
        assertEquals(null, failure.getCause());
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
        boolean authenticated = status != 401 && status != 403;
        assertEquals(new com.meetingautomation.document.ProviderHealth(true, authenticated, false),
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
