package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.meetingautomation.document.settings.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DocumentConnectionTesterTests {
    private static GlobalDocumentSettings.Draft draft(String provider) {
        var input = "NOTION".equals(provider) ? new DocumentDraftInput("NOTION", Map.of("parentPageId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), Map.of("token", "synthetic-secret"), null)
            : new DocumentDraftInput("CONFLUENCE", Map.of("baseUrl", "https://site.atlassian.net", "spaceId", "1", "parentPageId", "2"), Map.of("accountEmail", "synthetic@example.test", "apiToken", "synthetic-secret"), null);
        return new GlobalDocumentSettings.Draft("draft", 1, provider, input.location(), input.credentials(), null, "DRAFT", null, Instant.now().plusSeconds(86400));
    }
    @Test void setupTestNotionUsesOnlyAuthenticationAndParentRead() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.notion.com/v1/users/me")).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer synthetic-secret"))
            .andRespond(withSuccess("{\"type\":\"bot\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
            .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"object\":\"page\",\"archived\":false}", MediaType.APPLICATION_JSON));
        new HttpDocumentConnectionTester(builder).verify(draft("NOTION"));
        server.verify();
    }
    @Test void setupTestConfluenceUsesOnlySpaceAndParentRead() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://site.atlassian.net/wiki/api/v2/spaces/1")).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("synthetic@example.test:synthetic-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://site.atlassian.net/wiki/api/v2/pages/2")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"spaceId\":\"1\",\"status\":\"current\"}", MediaType.APPLICATION_JSON));
        new HttpDocumentConnectionTester(builder).verify(draft("CONFLUENCE"));
        server.verify();
    }
    @ParameterizedTest @ValueSource(ints = {401, 403, 429, 500})
    void setupTestProviderErrorsAreSafeAndNeverWrite(int status) {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.notion.com/v1/users/me")).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("synthetic-secret synthetic@example.test raw-provider-error"));
        var error = assertThrows(DocumentProviderException.class, () -> new HttpDocumentConnectionTester(builder).verify(draft("NOTION")));
        assertEquals("DOCUMENT_FAILED", error.code());
        assertEquals(status == 429 || status >= 500, error.retryable());
        assertFalse(error.getMessage().contains("synthetic")); assertNull(error.getCause());
        server.verify();
    }
    @Test void setupTestWrongParentSpaceIsRejectedWithoutWrites() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://site.atlassian.net/wiki/api/v2/spaces/1")).andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://site.atlassian.net/wiki/api/v2/pages/2")).andRespond(withSuccess("{\"spaceId\":\"99\",\"status\":\"current\"}", MediaType.APPLICATION_JSON));
        assertEquals(400, assertThrows(SettingsException.class, () -> new HttpDocumentConnectionTester(builder).verify(draft("CONFLUENCE"))).status());
        server.verify();
    }
    @Test void setupTestAuthenticationRedirectIsRejectedEvenWithMisleadingSuccessBody() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.notion.com/v1/users/me")).andRespond(withStatus(HttpStatus.FOUND)
            .header("Location", "https://untrusted.example").body("{\"type\":\"bot\"}").contentType(MediaType.APPLICATION_JSON));
        assertThrows(DocumentProviderException.class, () -> new HttpDocumentConnectionTester(builder).verify(draft("NOTION")));
        server.verify();
    }
}
