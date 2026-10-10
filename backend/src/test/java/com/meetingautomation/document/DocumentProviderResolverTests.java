package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.*;
import com.meetingautomation.DocumentSettingsFixtures;
import com.meetingautomation.document.settings.*;
import com.meetingautomation.document.notion.NotionPageHierarchyAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DocumentProviderResolverTests {
    @Test void emptyStoreDoesNotResolveDraftOrLegacyProvider() {
        GlobalSettingsStore store = DocumentSettingsFixtures.empty();
        new GlobalSettingsUseCase(store, draft -> {}).saveDraft(0, "key", new DocumentDraftInput("NOTION",
            java.util.Map.of("parentPageId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), java.util.Map.of("token", "synthetic-token"), null));
        DocumentProviderResolver resolver = new DocumentProviderResolver(store, new DocumentProviderFactory(RestClient.builder()));
        assertNull(resolver.providerId());
        assertFalse(resolver.configured());
        assertTrue(resolver.selectedProvider().isEmpty());
        assertEquals("DOCUMENT_SETUP_REQUIRED", assertThrows(SettingsException.class, resolver::requireActive).code());
    }
    @Test void resolverUsesDurableActiveConnection() {
        GlobalSettingsStore store = DocumentSettingsFixtures.activeNotion("synthetic-token", "synthetic-root");
        DocumentProviderResolver resolver = new DocumentProviderResolver(store, new DocumentProviderFactory(RestClient.builder()));
        assertEquals("NOTION", resolver.providerId());
        assertTrue(resolver.configured());
        assertEquals("synthetic-root", resolver.rootId());
        assertInstanceOf(NotionPageHierarchyAdapter.class, resolver.selectedProvider().orElseThrow());
    }
    @Test void storageFailureDoesNotResolveAnythingAndBlocksBusinessAccess() {
        GlobalSettingsStore store = new EncryptedFileGlobalSettingsStore("", "", false);
        DocumentProviderResolver resolver = new DocumentProviderResolver(store, new DocumentProviderFactory(RestClient.builder()));
        assertNull(resolver.providerId());
        assertFalse(resolver.configured());
        assertEquals(503, assertThrows(SettingsException.class, resolver::requireActive).status());
    }
}
