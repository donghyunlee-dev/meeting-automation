package com.meetingautomation.api.template;

import java.io.IOException;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Reads the required built-in minute templates and exposes only their public metadata. */
@Component
public final class TemplateCatalog {
    private static final List<TemplateMetadata> REQUIRED_TEMPLATES = List.of(
            new TemplateMetadata("default.md", "기본 회의록", "1.0.0"),
            new TemplateMetadata("project.md", "프로젝트 회의", "1.0.0"));

    private final ResourceLoader resourceLoader;

    public TemplateCatalog(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public TemplateListResponse listTemplates() {
        for (TemplateMetadata template : REQUIRED_TEMPLATES) {
            requireReadableResource(template.id());
        }
        return new TemplateListResponse(new TemplateListResponse.Data(REQUIRED_TEMPLATES));
    }

    private void requireReadableResource(String id) {
        Resource resource = resourceLoader.getResource("classpath:templates/" + id);
        try (var input = resource.getInputStream()) {
            input.readAllBytes();
        } catch (IOException | RuntimeException failure) {
            // Keep resource paths and filesystem details out of the common error response.
            throw new IllegalStateException("Required template resources are unavailable.");
        }
    }
}
