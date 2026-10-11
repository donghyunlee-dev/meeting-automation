package com.meetingautomation.api.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations")
public final class IntegrationHealthController {
    private final IntegrationHealthAggregator aggregator;

    public IntegrationHealthController(IntegrationHealthAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @GetMapping("/health")
    public IntegrationHealthResponse health() {
        return aggregator.health();
    }
}
