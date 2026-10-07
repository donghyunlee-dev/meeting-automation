package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.meetingautomation.api.error.ApiErrorResponse;
import com.meetingautomation.api.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DocumentProviderErrorMappingTests {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void structureFailureMapsToSafeNonRetryable422() {
        var response = handler.handleDocumentProviderFailure(
                DocumentProviderException.structureNotFound(), request());

        assertEquals(422, response.getStatusCode().value());
        assertEquals("DOCUMENT_STRUCTURE_NOT_FOUND", error(response).code());
        assertEquals("DOCUMENT_FAILURE", error(response).category());
        assertFalse(error(response).retryable());
        assertFalse(error(response).message().contains("provider-secret-marker"));
    }

    @Test
    void participantAndDocumentFailuresMapToSafe502Errors() {
        var participantResponse = handler.handleDocumentProviderFailure(
                DocumentProviderException.participantListFailed(true), request());
        assertEquals(502, participantResponse.getStatusCode().value());
        assertEquals("PARTICIPANT_LIST_FAILED", error(participantResponse).code());
        assertEquals("DOCUMENT_FAILURE", error(participantResponse).category());
        assertEquals(true, error(participantResponse).retryable());

        var documentResponse = handler.handleDocumentProviderFailure(
                DocumentProviderException.documentFailed(false), request());
        assertEquals(502, documentResponse.getStatusCode().value());
        assertEquals("DOCUMENT_FAILED", error(documentResponse).code());
        assertEquals("DOCUMENT_FAILURE", error(documentResponse).category());
        assertFalse(error(documentResponse).retryable());
        assertFalse(error(documentResponse).message().contains("raw-response-marker"));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "document-contract-test");
        return request;
    }

    private static com.meetingautomation.api.error.ApiError error(
            org.springframework.http.ResponseEntity<ApiErrorResponse> response) {
        return response.getBody().error();
    }
}
