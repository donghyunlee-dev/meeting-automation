package com.meetingautomation;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ErrorContractProbeController.class)
class MeetingAutomationApplicationTests {

    private static final String INPUT_MARKER = "sensitive-user-input-marker";
    private static final String EXCEPTION_MARKER = "internal-exception-secret-marker";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void validationErrorsUseSafeCommonEnvelopeAndReflectRequestId() throws Exception {
        mockMvc.perform(post("/__test/errors/validation")
                        .header("X-Request-Id", "test-request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + INPUT_MARKER + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.category").value("VALIDATION"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.traceId").value("test-request-123"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("value"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].code").value("Size"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains(INPUT_MARKER)));
    }

    @Test
    void validationErrorsGenerateTraceIdWhenRequestIdIsMissing() throws Exception {
        mockMvc.perform(post("/__test/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + INPUT_MARKER + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.traceId", startsWith("tr_")));
    }

    @Test
    void unexpectedErrorsUseSafeEnvelopeAndGenerateRequestId() throws Exception {
        mockMvc.perform(get("/__test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.category").value("INTERNAL"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.traceId", not(emptyOrNullString())))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains(EXCEPTION_MARKER));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("IllegalStateException"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("at com.meetingautomation"));
                });
    }

    @Test
    void missingRequiredHeaderKeepsClientErrorStatusAndReturnsSafeEnvelope() throws Exception {
        mockMvc.perform(get("/__test/errors/required-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.category").value("VALIDATION"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.traceId", startsWith("tr_")))
                .andExpect(jsonPath("$.error.details").isMap())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains("MissingRequestHeaderException")));
    }

    @Test
    void typeMismatchKeepsBadRequestStatusWithoutEchoingRejectedInput() throws Exception {
        mockMvc.perform(get("/__test/errors/type-mismatch")
                        .queryParam("count", INPUT_MARKER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.category").value("VALIDATION"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains(INPUT_MARKER));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("MethodArgumentTypeMismatchException"));
                });
    }

    @Test
    void unsupportedMediaTypeAndMethodKeepFrameworkStatuses() throws Exception {
        mockMvc.perform(post("/__test/errors/json-only")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(INPUT_MARKER))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.category").value("VALIDATION"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains(INPUT_MARKER)))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains("HttpMediaTypeNotSupportedException")));

        mockMvc.perform(get("/__test/errors/post-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.category").value("VALIDATION"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains("HttpRequestMethodNotSupportedException")));
    }

    @Test
    void healthIsUpWithoutDetailsAndOtherActuatorEndpointsAreHidden() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());

        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/actuator"))
                .andExpect(status().isNotFound());
    }
}
