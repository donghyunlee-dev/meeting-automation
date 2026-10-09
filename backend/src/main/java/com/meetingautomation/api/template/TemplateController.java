package com.meetingautomation.api.template;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class TemplateController {
    private final TemplateCatalog templateCatalog;

    public TemplateController(TemplateCatalog templateCatalog) {
        this.templateCatalog = templateCatalog;
    }

    @GetMapping("/templates")
    public TemplateListResponse templates() {
        return templateCatalog.listTemplates();
    }
}
