package com.meetingautomation.api.template;

import java.util.List;

public record TemplateListResponse(Data data) {
    public record Data(List<TemplateMetadata> items) {
    }
}
