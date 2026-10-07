package com.meetingautomation.api.health;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.ProviderHealth;
import org.springframework.stereotype.Component;

@Component
public final class DocumentHealthContributor {
    private final DocumentProviderResolver providerResolver;

    public DocumentHealthContributor(DocumentProviderResolver providerResolver) {
        this.providerResolver = providerResolver;
    }

    public DocumentHealthStatus health() {
        String providerId = providerResolver.providerId();
        if (!providerResolver.configured()) {
            return DocumentHealthStatus.unavailable(providerId, false);
        }

        DocumentStructureProvider provider = providerResolver.selectedProvider().orElse(null);
        if (provider == null) {
            return DocumentHealthStatus.unavailable(providerId, false);
        }

        try {
            ProviderHealth connection = provider.validateConnection();
            if (!connection.configured()) {
                return DocumentHealthStatus.unavailable(providerId, false);
            }
            if (!connection.reachable()) {
                return DocumentHealthStatus.unavailable(providerId, true);
            }
            if (!connection.rootAccessible()) {
                return new DocumentHealthStatus(providerId, true, true, false);
            }

            try {
                provider.discoverStructure(providerResolver.rootId());
                return new DocumentHealthStatus(providerId, true, true, true);
            } catch (DocumentProviderException failure) {
                boolean reachable = "DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code());
                return new DocumentHealthStatus(providerId, true, reachable, false);
            }
        } catch (RuntimeException unexpectedProviderFailure) {
            return DocumentHealthStatus.unavailable(providerId, true);
        }
    }
}
