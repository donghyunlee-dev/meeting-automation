package com.meetingautomation.api.participant;

import com.meetingautomation.api.idempotency.ParticipantCreationCoordinator;
import com.meetingautomation.document.CreateParticipantCommand;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantCreationProvider;
import com.meetingautomation.document.ParticipantListingProvider;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class ParticipantService {
    private final DocumentProviderResolver providerResolver;
    private final ParticipantCreationCoordinator creationCoordinator;

    public ParticipantService(
            DocumentProviderResolver providerResolver,
            ParticipantCreationCoordinator creationCoordinator) {
        this.providerResolver = providerResolver;
        this.creationCoordinator = creationCoordinator;
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

    public Participant createParticipant(String idempotencyKey, ParticipantCreateRequest request) {
        providerResolver.validateSelection();
        ParticipantCreationProvider provider = providerResolver.selectedProvider()
                .filter(ParticipantCreationProvider.class::isInstance)
                .map(ParticipantCreationProvider.class::cast)
                .orElseThrow(() -> new IllegalStateException("Selected provider cannot create participants."));
        CreateParticipantCommand command = new CreateParticipantCommand(request.name(), request.email());
        return creationCoordinator.execute(idempotencyKey.trim(), command, () -> {
            if (!providerResolver.configured()) {
                throw DocumentProviderException.structureNotFound();
            }
            var structure = provider.discoverParticipantCreateStructure(providerResolver.rootId());
            try {
                return provider.createParticipant(structure.participantsPageId(), command);
            } catch (DocumentProviderException failure) {
                throw failure;
            } catch (RuntimeException providerFailure) {
                throw DocumentProviderException.documentFailed(false, providerFailure);
            }
        });
    }
}
