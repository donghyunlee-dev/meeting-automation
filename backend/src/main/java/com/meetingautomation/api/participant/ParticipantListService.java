package com.meetingautomation.api.participant;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantListingProvider;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class ParticipantListService {
    private final DocumentProviderResolver providerResolver;

    public ParticipantListService(DocumentProviderResolver providerResolver) {
        this.providerResolver = providerResolver;
    }

    public List<Participant> listParticipants() {
        providerResolver.validateSelection();
        ParticipantListingProvider provider = providerResolver.selectedProvider()
                .filter(ParticipantListingProvider.class::isInstance)
                .map(ParticipantListingProvider.class::cast)
                .orElseThrow(DocumentProviderException::structureNotFound);
        if (!providerResolver.configured()) {
            throw DocumentProviderException.structureNotFound();
        }
        com.meetingautomation.document.DocumentStructure structure;
        try {
            structure = provider.discoverParticipantStructure(providerResolver.rootId());
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code())) {
                throw failure;
            }
            throw DocumentProviderException.participantListFailed(failure.retryable(), failure);
        }
        return List.copyOf(provider.listParticipants(structure.participantsPageId()));
    }
}
