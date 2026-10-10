package com.meetingautomation.api.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods = false)
public class DocumentSetupWebConfiguration implements WebMvcConfigurer {
    private final String[] origins;
    public DocumentSetupWebConfiguration(@Value("${ALLOWED_ORIGINS:}") String origins) {
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**").allowedOrigins(origins).allowedMethods("GET", "POST", "PUT", "PATCH", "OPTIONS")
            .allowedHeaders("Content-Type", "If-Match", "Idempotency-Key", "X-Document-Setup-Request", "X-Request-Id")
            .exposedHeaders("ETag").allowCredentials(false);
    }
}
