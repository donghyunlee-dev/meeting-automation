package com.meetingautomation.document;

import com.meetingautomation.document.settings.*;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Resolves only the durable active connection. Drafts and legacy env never select a provider. */
@Component
public final class DocumentProviderResolver {
    private final GlobalSettingsStore store;
    private final DocumentProviderFactory factory;
    public DocumentProviderResolver(GlobalSettingsStore store, DocumentProviderFactory factory) {
        this.store = store; this.factory = factory;
    }
    public void requireActive() {
        if (store.read().active() == null) throw new SettingsException("DOCUMENT_SETUP_REQUIRED", 409);
    }
    public Optional<GlobalDocumentSettings.Connection> active() {
        try { return Optional.ofNullable(store.read().active()); }
        catch (SettingsException failure) { return Optional.empty(); }
    }
    public String providerId() { return active().map(GlobalDocumentSettings.Connection::provider).orElse(null); }
    public boolean configured() { return active().isPresent(); }
    public Optional<DocumentStructureProvider> selectedProvider() { return active().map(factory::create); }
    public String rootId() { return active().map(GlobalDocumentSettings.Connection::rootId).orElse(null); }
}
