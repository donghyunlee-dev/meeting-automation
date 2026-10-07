package com.meetingautomation.document;

import com.meetingautomation.document.confluence.ConfluencePageHierarchyAdapter;
import com.meetingautomation.document.notion.NotionPageHierarchyAdapter;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves the single document provider selected by backend configuration. */
@Component
public final class DocumentProviderResolver {
    private final String provider;
    private final DocumentStructureProvider selectedProvider;
    private final boolean configured;
    private final String rootId;
    private final boolean selectionValid;

    public DocumentProviderResolver(
            @Value("${DOCUMENT_PROVIDER:}") String selection,
            @Value("${DOCUMENT_ROOT_ID:}") String rootId,
            NotionPageHierarchyAdapter notionProvider,
            ConfluencePageHierarchyAdapter confluenceProvider) {
        this.rootId = rootId;
        String normalized = selection == null ? "" : selection.trim();
        if (normalized.isEmpty()) {
            this.provider = null;
            this.selectedProvider = null;
            this.configured = false;
            this.selectionValid = true;
            return;
        }

        switch (normalized) {
            case "NOTION" -> {
                this.provider = normalized;
                this.selectedProvider = notionProvider;
                this.configured = notionProvider.isConfigured();
                this.selectionValid = true;
            }
            case "CONFLUENCE" -> {
                this.provider = normalized;
                this.selectedProvider = confluenceProvider;
                this.configured = confluenceProvider.isConfigured();
                this.selectionValid = true;
            }
            default -> {
                this.provider = null;
                this.selectedProvider = null;
                this.configured = false;
                this.selectionValid = false;
            }
        }
    }

    public void validateSelection() {
        if (!selectionValid) {
            throw new IllegalStateException("Unsupported DOCUMENT_PROVIDER configuration.");
        }
    }

    public String providerId() {
        return provider;
    }

    public boolean configured() {
        return configured;
    }

    public Optional<DocumentStructureProvider> selectedProvider() {
        return Optional.ofNullable(selectedProvider);
    }

    public String rootId() {
        return rootId;
    }
}
