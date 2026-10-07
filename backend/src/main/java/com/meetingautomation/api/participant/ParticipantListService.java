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
        ParticipantListingProvider provider = providerResolver.selectedProvider()
                .filter(ParticipantListingProvider.class::isInstance)
                .map(ParticipantListingProvider.class::cast)
                .orElseThrow(DocumentProviderException::structureNotFound);
        if (!providerResolver.configured()) {
            throw DocumentProviderException.structureNotFound();
        }
        var structure = provider.discoverStructure(providerResolver.rootId());
        return List.copyOf(provider.listParticipants(structure.participantsPageId()));
    }
}
