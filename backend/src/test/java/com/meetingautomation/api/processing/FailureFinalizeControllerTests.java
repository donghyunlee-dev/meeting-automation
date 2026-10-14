package com.meetingautomation.api.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FailureFinalizeControllerTests {
    @Test
    void returnsAcceptedAfterFailureDocumentIsFinalized() {
        FailureFinalizeService service = mock(FailureFinalizeService.class);
        FailureFinalizeRequest request = new FailureFinalizeRequest("DISCARDED");
        when(service.finalizeFailure("session-1", "\"4\"", "finalize-1", request))
                .thenReturn(new FailureFinalizeResponse(new FailureFinalizeResponse.Data(
                        "session-1", "COMPLETED_WITH_WARNINGS", 5, "DISCARDED", "document-1", null)));
        FailureFinalizeController controller = new FailureFinalizeController(service);

        var response = controller.finalizeFailure("session-1", "\"4\"", "finalize-1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().data().status()).isEqualTo("COMPLETED_WITH_WARNINGS");
    }
}
