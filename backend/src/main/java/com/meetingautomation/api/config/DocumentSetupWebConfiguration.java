package com.meetingautomation.api.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods = false)
public class DocumentSetupWebConfiguration implements WebMvcConfigurer {
    private static final String LOCAL_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173";
    private final String[] origins;
    public DocumentSetupWebConfiguration(@Value("${ALLOWED_ORIGINS:}") String origins) {
        String configuredOrigins = origins.isBlank() ? LOCAL_ORIGINS : origins;
        this.origins = Arrays.stream(configuredOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**").allowedOrigins(origins).allowedMethods("GET", "POST", "PUT", "PATCH", "OPTIONS")
            .allowedHeaders("Accept", "Content-Type", "If-Match", "Idempotency-Key", "X-Audio-SHA256",
                "X-Audio-Byte-Length", "X-Document-Setup-Request", "X-Request-Id")
            .exposedHeaders("ETag").allowCredentials(false);
    }
}
