package com.meetingautomation.api.health;

import com.meetingautomation.document.DocumentProviderResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations")
public final class IntegrationHealthController {
    private final IntegrationHealthAggregator aggregator;
    private final DocumentProviderResolver providerResolver;

    public IntegrationHealthController(
            IntegrationHealthAggregator aggregator,
            DocumentProviderResolver providerResolver) {
        this.aggregator = aggregator;
        this.providerResolver = providerResolver;
    }

    @GetMapping("/health")
    public IntegrationHealthResponse health() {
        providerResolver.validateSelection();
        return aggregator.health();
    }
}
