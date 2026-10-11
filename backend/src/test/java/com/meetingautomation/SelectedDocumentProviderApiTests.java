package com.meetingautomation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
import java.util.List;

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
    void appConfigReturnsActiveProviderAndSafeLocationWithoutCredentialValues() throws Exception {
        mockMvc.perform(get("/api/v1/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document.provider").value("NOTION"))
                .andExpect(jsonPath("$.data.document.configured").value(true))
                .andExpect(jsonPath("$.data.notification.provider").value("SLACK"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains(TOKEN));
                });
    }

    @Test
    void validParticipantCreateReturnsNormalizedResourceAndCreatesNotionPage() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header("Notion-Version", "2026-03-11"))
                .andExpect(content().json("""
                        {
                          "parent":{"page_id":"participants-page"},
                          "properties":{"title":{"title":[{"text":{"content":"Ada Lovelace"}}]}},
                          "children":[{"object":"block","type":"paragraph","paragraph":{"rich_text":[
                            {"type":"text","text":{"content":"Email: ada@example.test"}}
                          ]}}]
                        }
                        """))
                .andRespond(jsonSuccess("{\"id\":\"created-participant-id\"}"));

        mockMvc.perform(post("/api/v1/participants")
                        .header("Idempotency-Key", "create-participant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Ada Lovelace \",\"email\":\" ada@example.test \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("created-participant-id"))
                .andExpect(jsonPath("$.data.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.data.email").value("ada@example.test"));
        mockServer.verify();
    }

    @Test
    void createdNotionParticipantCanBeReadBackByRosterList() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(jsonSuccess("{\"id\":\"created-roundtrip-id\"}"));
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"id\":\"created-roundtrip-id\",\"type\":\"child_page\","
                        + "\"child_page\":{\"title\":\"Ada Lovelace\"}}]}"));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/created-roundtrip-id/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":["
                        + "{\"plain_text\":\"Email: ada@example.test\"}]}}]}"));

        mockMvc.perform(createParticipantRequest("roundtrip-key", "Ada Lovelace", "ada@example.test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("created-roundtrip-id"));
        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("created-roundtrip-id"))
                .andExpect(jsonPath("$.data.items[0].name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.data.items[0].email").value("ada@example.test"));
        mockServer.verify();
    }

    @Test
    void normalizedDuplicateRequestReplaysFirstCreateWithoutAnotherProviderCall() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(jsonSuccess("{\"id\":\"created-once\"}"));

        mockMvc.perform(createParticipantRequest("same-key", "Ada", "ada@example.test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("created-once"));
        mockMvc.perform(createParticipantRequest("same-key", " Ada ", " ada@example.test "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("created-once"));
        mockServer.verify();
    }

    @Test
    void sameKeyWithDifferentNormalizedPayloadReturnsConflictWithoutCreatingAgain() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(jsonSuccess("{\"id\":\"created-once\"}"));

        mockMvc.perform(createParticipantRequest("different-payload-key", "Ada", "ada@example.test"))
                .andExpect(status().isCreated());
        mockMvc.perform(createParticipantRequest("different-payload-key", "Grace", "grace@example.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        mockServer.verify();
    }

    @Test
    void invalidCreateFieldsAreRejectedBeforeProviderCalls() throws Exception {
        mockMvc.perform(post("/api/v1/participants").header("Idempotency-Key", "invalid-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"email\":\"ada@example.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/participants").header("Idempotency-Key", "invalid-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/participants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada\",\"email\":\"ada@example.test\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockServer.verify();
    }

    @Test
    void missingParticipantStructureReturns422BeforeCreate() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(
                "{\"has_more\":false,\"next_cursor\":null,\"results\":[]}"));

        mockMvc.perform(createParticipantRequest("missing-structure", "Ada", "ada@example.test"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_STRUCTURE_NOT_FOUND"));
        mockServer.verify();
    }

    @Test
    void transientStructurePreflightFailureIsRetryableAndDoesNotAttemptCreate() throws Exception {
        mockServer.expect(notionRequest())
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"provider-secret-marker\"}"));

        mockMvc.perform(createParticipantRequest("preflight-transient-key", "Ada", "ada@example.test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.error.category").value("DOCUMENT_FAILURE"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString()
                        .contains("provider-secret-marker")));
        mockServer.verify();
    }

    @Test
    void uncertainCreateFailureIsCachedAndNeverRetriedForSameKey() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> { throw new java.io.IOException("uncertain-create-secret"); });

        mockMvc.perform(createParticipantRequest("uncertain-key", "Ada", "ada@example.test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        mockMvc.perform(createParticipantRequest("uncertain-key", "Ada", "ada@example.test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString()
                        .contains("uncertain-create-secret")));
        mockServer.verify();
    }

    @Test
    void rateLimitedCreateReturnsRetryableDocumentFailureAndReplaysSameOutcome() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + TOKEN + " rate-limit-marker\"}"));

        mockMvc.perform(createParticipantRequest("rate-limited-key", "Ada", "ada@example.test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.error.category").value("DOCUMENT_FAILURE"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains(TOKEN));
                    assertFalse(body.contains("rate-limit-marker"));
                });
        mockMvc.perform(createParticipantRequest("rate-limited-key", "Ada", "ada@example.test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.retryable").value(true));
        mockServer.verify();
    }

    @Test
    void permanentCreateFailureIsNonRetryable() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + TOKEN + " permission-marker\"}"));

        mockMvc.perform(createParticipantRequest("permanent-create-key", "Ada", "ada@example.test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains(TOKEN));
                    assertFalse(body.contains("permission-marker"));
                });
        mockServer.verify();
    }

    @Test
    void participantNameOnlyPatchPreservesExistingEmailAndUpdatesNotionTitle() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"id\":\"person-page-id\",\"type\":\"child_page\","
                        + "\"child_page\":{\"title\":\"Old Name\"}}]}"));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/person-page-id/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"id\":\"email-block-id\",\"type\":\"paragraph\",\"paragraph\":{"
                        + "\"rich_text\":[{\"plain_text\":\"Email: old@example.test\"}]}}]}"));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages/person-page-id"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {"properties":{"title":{"title":[{"text":{"content":"New Name"}}]}}}
                        """))
                .andRespond(jsonSuccess("{\"id\":\"person-page-id\"}"));

        mockMvc.perform(patch("/api/v1/participants/person-page-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" New Name \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("person-page-id"))
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.email").value("old@example.test"));
        mockServer.verify();
    }

    @Test
    void participantEmailOnlyPatchPreservesNameAndOtherNotionBodyText() throws Exception {
        String roster = "https://api.notion.com/v1/blocks/participants-page/children";
        String pageChildren = "https://api.notion.com/v1/blocks/person-page-id/children";
        String currentText = "Email: old@example.test\nRole: Engineer";
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo(roster)).andRespond(jsonSuccess(participantChildPage()));
        mockServer.expect(notionRequestTo(pageChildren)).andRespond(jsonSuccess(participantText(currentText)));
        mockServer.expect(notionRequestTo(pageChildren)).andRespond(jsonSuccess(participantText(currentText)));
        mockServer.expect(requestTo("https://api.notion.com/v1/blocks/email-block-id"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {"paragraph":{"rich_text":[
                          {"type":"text","text":{"content":"Email: "}},
                          {"type":"text","text":{"content":"new@example.test"}},
                          {"type":"text","text":{"content":"\\nRole: Engineer"}}
                        ]}}
                        """))
                .andRespond(jsonSuccess("{\"id\":\"email-block-id\"}"));

        mockMvc.perform(patch("/api/v1/participants/person-page-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" new@example.test \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("person-page-id"))
                .andExpect(jsonPath("$.data.name").value("Old Name"))
                .andExpect(jsonPath("$.data.email").value("new@example.test"));
        mockServer.verify();
    }

    @Test
    void participantDeletedAfterRosterReadReturnsSafeNotFoundWithoutMutation() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(jsonSuccess(participantChildPage()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/person-page-id/children"))
                .andRespond(jsonSuccess(participantText("Email: old@example.test")));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/person-page-id/children"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"notion-page-secret-marker\"}"));

        mockMvc.perform(patch("/api/v1/participants/person-page-id")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"new@example.test\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_NOT_FOUND"))
                .andExpect(result -> {
                    String response = result.getResponse().getContentAsString();
                    assertFalse(response.contains("notion-page-secret-marker"));
                    assertFalse(response.contains(TOKEN));
                });
        mockServer.verify();
    }

    @Test
    void invalidParticipantPatchBodiesAreRejectedBeforeProviderCalls() throws Exception {
        mockMvc.perform(patch("/api/v1/participants/person-page-id")
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        for (String body : List.of("{}", "{\"name\":null}", "{\"email\":null}",
                "{\"name\":\"  \"}", "{\"email\":\"invalid\"}")) {
            mockMvc.perform(patch("/api/v1/participants/person-page-id")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockServer.verify();
    }

    @Test
    void participantOutsideSelectedRosterReturns404WithoutMutation() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":[]}"));

        mockMvc.perform(patch("/api/v1/participants/not-a-roster-member")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New Name\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_NOT_FOUND"));
        mockServer.verify();
    }

    @Test
    void participantUpdateMapsRosterReadFailureToSafeDocumentFailure() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + TOKEN + " roster-read-marker\"}"));

        mockMvc.perform(patch("/api/v1/participants/person-page-id")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New Name\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(result -> {
                    String response = result.getResponse().getContentAsString();
                    assertFalse(response.contains(TOKEN));
                    assertFalse(response.contains("roster-read-marker"));
                });
        mockServer.verify();
    }

    @Test
    void participantUpdateMissingStructureReturns422BeforeRosterReadOrMutation() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(
                "{\"has_more\":false,\"next_cursor\":null,\"results\":[]}"));

        mockMvc.perform(patch("/api/v1/participants/person-page-id")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New Name\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_STRUCTURE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        mockServer.verify();
    }

    @Test
    void participantBothFieldsPatchReturnsCompleteUpdatedDto() throws Exception {
        String roster = "https://api.notion.com/v1/blocks/participants-page/children";
        String pageChildren = "https://api.notion.com/v1/blocks/person-page-id/children";
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo(roster)).andRespond(jsonSuccess(participantChildPage()));
        mockServer.expect(notionRequestTo(pageChildren)).andRespond(jsonSuccess(participantText("Email: old@example.test")));
        mockServer.expect(notionRequestTo(pageChildren)).andRespond(jsonSuccess(participantText("Email: old@example.test")));
        mockServer.expect(requestTo("https://api.notion.com/v1/blocks/email-block-id"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(jsonSuccess("{\"id\":\"email-block-id\"}"));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages/person-page-id"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(jsonSuccess("{\"id\":\"person-page-id\"}"));

        mockMvc.perform(patch("/api/v1/participants/person-page-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\",\"email\":\"new@example.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("person-page-id"))
                .andExpect(jsonPath("$.data.name").value("New Name"))
                .andExpect(jsonPath("$.data.email").value("new@example.test"));
        mockServer.verify();
    }

    @Test
    void failedSecondNotionMutationReturnsSafeNonRetryableFailure() throws Exception {
        String roster = "https://api.notion.com/v1/blocks/participants-page/children";
        String pageChildren = "https://api.notion.com/v1/blocks/person-page-id/children";
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo(roster)).andRespond(jsonSuccess(participantChildPage()));
        mockServer.expect(notionRequestTo(pageChildren)).andRespond(jsonSuccess(participantText("Email: old@example.test")));
        mockServer.expect(notionRequestTo(pageChildren)).andRespond(jsonSuccess(participantText("Email: old@example.test")));
        mockServer.expect(requestTo("https://api.notion.com/v1/blocks/email-block-id"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(jsonSuccess("{\"id\":\"email-block-id\"}"));
        mockServer.expect(requestTo("https://api.notion.com/v1/pages/person-page-id"))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(request -> { throw new java.io.IOException("mutation-outcome-secret-marker"); });

        mockMvc.perform(patch("/api/v1/participants/person-page-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\",\"email\":\"new@example.test\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(result -> {
                    String response = result.getResponse().getContentAsString();
                    assertFalse(response.contains("mutation-outcome-secret-marker"));
                    assertFalse(response.contains(TOKEN));
                });
        mockServer.verify();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createParticipantRequest(
            String idempotencyKey, String name, String email) {
        return post("/api/v1/participants")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}");
    }

    @Test
    void participantsApiReturnsRosterItemsMappedFromProviderPages() throws Exception {
        mockServer.expect(notionRequest())
                .andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"id\":\"person-page-id\",\"type\":\"child_page\","
                        + "\"child_page\":{\"title\":\"Ada Lovelace\"}}]}"));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/person-page-id/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":["
                        + "{\"plain_text\":\"Email: ada@\"},{\"plain_text\":\"example.test\"}]}}]}"));

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("person-page-id"))
                .andExpect(jsonPath("$.data.items[0].name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.data.items[0].email").value("ada@example.test"))
                .andExpect(jsonPath("$.data.items[0].length()").value(3));
        mockServer.verify();
    }

    @Test
    void emptyParticipantsPageReturnsEmptyItems() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":[]}"));

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mockServer.verify();
    }

    @Test
    void missingParticipantsStructureReturnsSafe422() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(
                "{\"has_more\":false,\"next_cursor\":null,\"results\":[]}"));

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_STRUCTURE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        mockServer.verify();
    }

    @Test
    void participantProviderFailureReturnsSanitizedRetryableError() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + TOKEN + " private-provider-marker\"}"));

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"))
                .andExpect(jsonPath("$.error.category").value("DOCUMENT_FAILURE"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains(TOKEN));
                    assertFalse(body.contains("private-provider-marker"));
                });
        mockServer.verify();
    }

    @Test
    void transientStructureLookupFailureUsesParticipantListContract() throws Exception {
        mockServer.expect(notionRequest())
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + TOKEN + " structure-provider-marker\"}"));

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains(TOKEN));
                    assertFalse(body.contains("structure-provider-marker"));
                });
        mockServer.verify();
    }

    @Test
    void participantStructureTransportFailureIsRetryableAndSanitized() throws Exception {
        mockServer.expect(notionRequest()).andRespond(request -> {
            throw new java.io.IOException("transport-secret-marker");
        });

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("transport-secret-marker"));
                    assertFalse(body.contains(TOKEN));
                });
        mockServer.verify();
    }

    @Test
    void malformedTypedParticipantChildFailsWholeRoster() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"id\":\"good-id\",\"type\":\"child_page\","
                        + "\"child_page\":{\"title\":\"Valid Person\"}},"
                        + "{\"type\":\"child_page\",\"child_page\":{\"title\":\"Malformed Person\"}}]}"));

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("Valid Person")));
        mockServer.verify();
    }

    @Test
    void permanentParticipantProviderFailureIsNotRetryable() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + TOKEN + " access-denied-marker\"}"));

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains(TOKEN));
                    assertFalse(body.contains("access-denied-marker"));
                });
        mockServer.verify();
    }

    @Test
    void malformedParticipantPageFailsTheWholeListWithoutLeakingPageText() throws Exception {
        mockServer.expect(notionRequest()).andRespond(jsonSuccess(requiredChildren()));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/participants-page/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"id\":\"person-page-id\",\"type\":\"child_page\","
                        + "\"child_page\":{\"title\":\"Ada Lovelace\"}}]}"));
        mockServer.expect(notionRequestTo("https://api.notion.com/v1/blocks/person-page-id/children"))
                .andRespond(jsonSuccess("{\"has_more\":false,\"next_cursor\":null,\"results\":["
                        + "{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":["
                        + "{\"plain_text\":\"Email unavailable private-page-marker\"}]}}]}"));

        mockMvc.perform(get("/api/v1/participants"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertFalse(body.contains("private-page-marker"));
                    assertFalse(body.contains("Email unavailable"));
                });
        mockServer.verify();
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
                    assertFalse(body.contains(TOKEN));
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
        return notionRequestTo(URL);
    }

    private static org.springframework.test.web.client.RequestMatcher notionRequestTo(String url) {
        return request -> {
            requestTo(url).match(request);
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

    private static String participantChildPage() {
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":["
                + "{\"id\":\"person-page-id\",\"type\":\"child_page\","
                + "\"child_page\":{\"title\":\"Old Name\"}}]}";
    }

    private static String participantText(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"has_more\":false,\"next_cursor\":null,\"results\":["
                + "{\"id\":\"email-block-id\",\"type\":\"paragraph\",\"paragraph\":{"
                + "\"rich_text\":[{\"plain_text\":\"" + escaped + "\"}]}}]}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MockProviderHttpConfiguration {
        @Bean @Primary
        com.meetingautomation.document.settings.GlobalSettingsStore activeFixture() {
            return DocumentSettingsFixtures.activeNotion(TOKEN, ROOT_ID);
        }
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
