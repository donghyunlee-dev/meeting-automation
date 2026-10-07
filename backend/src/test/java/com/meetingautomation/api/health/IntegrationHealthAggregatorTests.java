package com.meetingautomation.api.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class IntegrationHealthAggregatorTests {
    @Test
    void oneContributorFailureDoesNotSuppressOtherAreasOrStableResponseKeys() {
        DocumentHealthContributor documentContributor = mock(DocumentHealthContributor.class);
        when(documentContributor.health()).thenReturn(new DocumentHealthStatus("CONFLUENCE", true, true, false));

        IntegrationHealthContributor emailFailure = new IntegrationHealthContributor() {
            @Override
            public IntegrationArea area() {
                return IntegrationArea.EMAIL;
            }

            @Override
            public IntegrationHealthStatus health() {
                throw new IllegalStateException("secret-provider-error-marker");
            }
        };
        IntegrationHealthContributor aiContributor = new IntegrationHealthContributor() {
            @Override
            public IntegrationArea area() {
                return IntegrationArea.AI;
            }

            @Override
            public IntegrationHealthStatus health() {
                return new IntegrationHealthStatus(true, true);
            }
        };

        IntegrationHealthResponse response = new IntegrationHealthAggregator(
                documentContributor, List.of(emailFailure, aiContributor), "SLACK").health();

        assertEquals(new DocumentHealthStatus("CONFLUENCE", true, true, false), response.data().document());
        assertEquals(IntegrationHealthStatus.unavailable(), response.data().email());
        assertEquals(new IntegrationHealthResponse.NotificationHealthStatus("SLACK", false, false),
                response.data().notification());
        assertEquals(new IntegrationHealthStatus(true, true), response.data().ai());
    }
}
