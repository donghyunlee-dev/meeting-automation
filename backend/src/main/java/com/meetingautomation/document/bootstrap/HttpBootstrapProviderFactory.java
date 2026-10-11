package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.settings.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public final class HttpBootstrapProviderFactory implements BootstrapProviderFactory {
    private final RestClient.Builder builder;
    public HttpBootstrapProviderFactory(RestClient.Builder builder) { this.builder = builder; }
    public BootstrapPages create(GlobalDocumentSettings.Draft draft) {
        return switch (draft.provider()) {
            case "NOTION" -> new NotionBootstrapPages(builder.clone(),draft.credentials().get("token"));
            case "CONFLUENCE" -> new ConfluenceBootstrapPages(builder.clone(),draft.location().get("baseUrl"),
                draft.location().get("spaceId"),draft.credentials().get("accountEmail"),draft.credentials().get("apiToken"));
            default -> throw SettingsException.invalid();
        };
    }
}
