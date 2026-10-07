package com.meetingautomation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@SpringBootTest(properties = {
        "DOCUMENT_PROVIDER=NOTION",
        "DOCUMENT_ROOT_ID=root-secret-marker",
        "NOTION_TOKEN=notion-token-marker",
        "NOTIFICATION_PROVIDER=SLACK"
})
@AutoConfigureMockMvc
@Import({SelectedDocumentProviderApiTests.MockProviderHttpConfiguration.class,
        ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class SelectedDocumentProviderApiTests {
    private static final String ROOT_ID = "root-secret-marker";
    private static final String TOKEN = "notion-token-marker";
    private static final String URL = "https://api.notion.com/v1/blocks/" + ROOT_ID + "/children";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MockRestServiceServer mockServer;

    @BeforeEach
    void resetMockServer() {
        mockServer.reset();
    }

    @Test
    void appConfigReturnsSelectedProviderWithoutCredentialOrRootValues() throws Exception {
        mockMvc.perform(get("/api/v1/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.provider").value("NOTION"))
                .andExpect(jsonPath("$.data.document.configured").value(true))
                .andExpect(jsonPath("$.data.notification.provider").value("SLACK"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains(TOKEN));
                    assertFalse(body.contains(ROOT_ID));
                });
    }

    @Test
    void authenticatedDocumentHealthChecksStructureAndKeepsOtherAreasPresent() throws Exception {
        mockServer.expect(notionRequest())
                .andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequest())
                .andRespond(jsonSuccess(requiredChildren()));

        mockMvc.perform(get("/api/v1/integrations/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.provider").value("NOTION"))
                .andExpect(jsonPath("$.data.document.configured").value(true))
                .andExpect(jsonPath("$.data.document.reachable").value(true))
                .andExpect(jsonPath("$.data.document.rootAccessible").value(true))
                .andExpect(jsonPath("$.data.email.configured").value(false))
                .andExpect(jsonPath("$.data.email.reachable").value(false))
                .andExpect(jsonPath("$.data.notification.provider").value("SLACK"))
                .andExpect(jsonPath("$.data.notification.configured").value(false))
                .andExpect(jsonPath("$.data.notification.reachable").value(false))
                .andExpect(jsonPath("$.data.ai.configured").value(false))
                .andExpect(jsonPath("$.data.ai.reachable").value(false));
        mockServer.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationFailureIsAHealthStateAndDoesNotExposeCredentialsOrVendorBody(int statusCode)
            throws Exception {
        mockServer.expect(notionRequest()).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(statusCode))
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"" + TOKEN
                        + " provider-error-marker\"}"));

        mockMvc.perform(get("/api/v1/integrations/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.provider").value("NOTION"))
                .andExpect(jsonPath("$.data.document.configured").value(true))
                .andExpect(jsonPath("$.data.document.reachable").value(false))
                .andExpect(jsonPath("$.data.document.rootAccessible").value(false))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains(TOKEN));
                    assertFalse(body.contains(ROOT_ID));
                    assertFalse(body.contains("provider-error-marker"));
                });
        mockServer.verify();
    }

    @Test
    void missingRootResponseIsReachableButNotRootAccessible() throws Exception {
        mockServer.expect(notionRequest()).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"private-root-marker\"}"));

        mockMvc.perform(get("/api/v1/integrations/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.configured").value(true))
                .andExpect(jsonPath("$.data.document.reachable").value(true))
                .andExpect(jsonPath("$.data.document.rootAccessible").value(false))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("private-root-marker"));
                    assertFalse(body.contains(ROOT_ID));
                });
        mockServer.verify();
    }

    @Test
    void incompleteRootStructureIsReturnedAsStatusWithoutFailingHealthEndpoint() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":[]}"));
        mockServer.expect(notionRequest()).andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":[]}"));

        mockMvc.perform(get("/api/v1/integrations/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.configured").value(true))
                .andExpect(jsonPath("$.data.document.reachable").value(true))
                .andExpect(jsonPath("$.data.document.rootAccessible").value(false))
                .andExpect(jsonPath("$.data.email.configured").value(false))
                .andExpect(jsonPath("$.data.ai.configured").value(false));
        mockServer.verify();
    }

    private static org.springframework.test.web.client.RequestMatcher notionRequest() {
        return request -> {
            requestTo(URL).match(request);
            method(HttpMethod.GET).match(request);
            header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN).match(request);
            header("Notion-Version", "2026-03-11").match(request);
        };
    }

    private static org.springframework.test.web.client.ResponseCreator jsonSuccess(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String requiredChildren() {
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":["
                + "{\"id\":\"meetings-page\",\"type\":\"child_page\",\"child_page\":{\"title\":\"Meetings\"}},"
                + "{\"id\":\"participants-page\",\"type\":\"child_page\",\"child_page\":{\"title\":\"Participants\"}}]}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MockProviderHttpConfiguration {
        @Bean
        MockProviderHttp mockProviderHttp() {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            return new MockProviderHttp(builder, server);
        }

        @Bean
        @Primary
        RestClient.Builder providerTestRestClientBuilder(MockProviderHttp mockProviderHttp) {
            return mockProviderHttp.builder();
        }

        @Bean
        MockRestServiceServer providerTestMockServer(MockProviderHttp mockProviderHttp) {
            return mockProviderHttp.server();
        }
    }

    record MockProviderHttp(RestClient.Builder builder, MockRestServiceServer server) {
    }
}
