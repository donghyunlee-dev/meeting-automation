package com.meetingautomation.api.template;

/** Static template content used by the processing application, never returned by the public catalog API. */
public record TemplateDefinition(String id, String version, String content) {
    public TemplateDefinition {
        if (id == null || id.isBlank() || version == null || version.isBlank()
                || content == null || content.isBlank()) {
            throw new IllegalArgumentException("Template definition is invalid.");
        }
    }

    @Override
    public String toString() {
        return "TemplateDefinition[id=" + id + ", version=" + version + ", content=<redacted>]";
    }
}
