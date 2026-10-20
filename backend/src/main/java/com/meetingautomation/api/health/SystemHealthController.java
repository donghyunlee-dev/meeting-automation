package com.meetingautomation.api.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public final class SystemHealthController {
    @GetMapping("/health")
    public SystemHealthResponse health() {
        return new SystemHealthResponse(new SystemHealthResponse.Data("UP"));
    }

    public record SystemHealthResponse(Data data) {
        public record Data(String status) {}
    }
}
