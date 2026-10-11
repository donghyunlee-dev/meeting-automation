package com.meetingautomation.document.settings;

import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.notion.NotionPageHierarchyAdapter;
import com.meetingautomation.document.confluence.ConfluencePageHierarchyAdapter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public final class DocumentProviderFactory {
    private final RestClient.Builder builder;
    public DocumentProviderFactory(RestClient.Builder builder) { this.builder = builder; }
    public DocumentStructureProvider create(GlobalDocumentSettings.Connection connection) {
        return switch (connection.provider()) {
            case "NOTION" -> new NotionPageHierarchyAdapter(builder.clone(), connection.credentials().get("token"), connection.rootId());
            case "CONFLUENCE" -> new ConfluencePageHierarchyAdapter(builder.clone(), connection.location().get("baseUrl"),
                connection.credentials().get("accountEmail"), connection.credentials().get("apiToken"), connection.rootId());
            default -> throw SettingsException.storage();
        };
    }
}
