package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meetingautomation.document.confluence.ConfluencePageHierarchyAdapter;
import com.meetingautomation.document.notion.NotionPageHierarchyAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DocumentProviderResolverTests {
    private static final String ROOT_ID = "12345";

    @Test
    void blankSelectionDoesNotFallBackToAConfiguredProvider() {
        NotionPageHierarchyAdapter notion = notion("notion-token", ROOT_ID);
        ConfluencePageHierarchyAdapter confluence = confluence(
                "https://site.atlassian.net", "service@example.com", "confluence-token", ROOT_ID);

        DocumentProviderResolver resolver = new DocumentProviderResolver("  ", ROOT_ID, notion, confluence);

        assertEquals(null, resolver.providerId());
        assertFalse(resolver.configured());
        assertTrue(resolver.selectedProvider().isEmpty());
    }

    @Test
    void notionSelectionUsesOnlyNotionConfiguration() {
        NotionPageHierarchyAdapter notion = notion("notion-token", ROOT_ID);
        ConfluencePageHierarchyAdapter confluence = confluence("invalid-base", "bad-email", "", ROOT_ID);

        DocumentProviderResolver resolver = new DocumentProviderResolver("NOTION", ROOT_ID, notion, confluence);

        assertEquals("NOTION", resolver.providerId());
        assertTrue(resolver.configured());
        assertInstanceOf(NotionPageHierarchyAdapter.class, resolver.selectedProvider().orElseThrow());
    }

    @Test
    void confluenceSelectionUsesOnlyConfluenceConfiguration() {
        NotionPageHierarchyAdapter notion = notion("", ROOT_ID);
        ConfluencePageHierarchyAdapter confluence = confluence(
                "https://site.atlassian.net", "service@example.com", "confluence-token", ROOT_ID);

        DocumentProviderResolver resolver = new DocumentProviderResolver("CONFLUENCE", ROOT_ID, notion, confluence);

        assertEquals("CONFLUENCE", resolver.providerId());
        assertTrue(resolver.configured());
        assertInstanceOf(ConfluencePageHierarchyAdapter.class, resolver.selectedProvider().orElseThrow());
    }

    @Test
    void selectedProviderStaysSelectedWhenItsConfigurationIsInvalid() {
        NotionPageHierarchyAdapter notion = notion("", ROOT_ID);
        ConfluencePageHierarchyAdapter confluence = confluence(
                "https://site.atlassian.net", "service@example.com", "confluence-token", ROOT_ID);

        DocumentProviderResolver resolver = new DocumentProviderResolver("NOTION", ROOT_ID, notion, confluence);

        assertEquals("NOTION", resolver.providerId());
        assertFalse(resolver.configured());
        assertInstanceOf(NotionPageHierarchyAdapter.class, resolver.selectedProvider().orElseThrow());
    }

    @Test
    void unsupportedNonblankSelectionFailsConfigurationValidationWhenResolved() {
        NotionPageHierarchyAdapter notion = notion("notion-token", ROOT_ID);
        ConfluencePageHierarchyAdapter confluence = confluence(
                "https://site.atlassian.net", "service@example.com", "confluence-token", ROOT_ID);

        DocumentProviderResolver resolver = new DocumentProviderResolver("NOTIONN", ROOT_ID, notion, confluence);
        assertThrows(IllegalStateException.class, resolver::validateSelection);
    }

    private static NotionPageHierarchyAdapter notion(String token, String rootId) {
        return new NotionPageHierarchyAdapter(RestClient.builder(), token, rootId);
    }

    private static ConfluencePageHierarchyAdapter confluence(
            String baseUrl, String email, String token, String rootId) {
        return new ConfluencePageHierarchyAdapter(RestClient.builder(), baseUrl, email, token, rootId);
    }
}
