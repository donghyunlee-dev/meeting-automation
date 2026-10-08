package com.meetingautomation.api.participant;

import com.meetingautomation.api.idempotency.ParticipantCreationCoordinator;
import com.meetingautomation.document.CreateParticipantCommand;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantCreationProvider;
import com.meetingautomation.document.ParticipantListingProvider;
import com.meetingautomation.document.ParticipantUpdateCommand;
import com.meetingautomation.document.ParticipantUpdatingProvider;
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

    public Participant updateParticipant(String participantId, ParticipantUpdateRequest request) {
        providerResolver.validateSelection();
        ParticipantUpdatingProvider provider = providerResolver.selectedProvider()
                .filter(ParticipantUpdatingProvider.class::isInstance)
                .map(ParticipantUpdatingProvider.class::cast)
                .orElseThrow(() -> new IllegalStateException("Selected provider cannot update participants."));
        if (!providerResolver.configured()) {
            throw DocumentProviderException.structureNotFound();
        }

        com.meetingautomation.document.DocumentStructure structure;
        try {
            structure = provider.discoverStructure(providerResolver.rootId());
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code())) {
                throw failure;
            }
            throw DocumentProviderException.documentFailed(failure.retryable(), failure);
        } catch (RuntimeException providerFailure) {
            throw DocumentProviderException.documentFailed(false, providerFailure);
        }

        List<Participant> roster;
        try {
            roster = List.copyOf(provider.listParticipants(structure.participantsPageId()));
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code())) {
                throw failure;
            }
            throw DocumentProviderException.documentFailed(failure.retryable(), failure);
        } catch (RuntimeException providerFailure) {
            throw DocumentProviderException.documentFailed(false, providerFailure);
        }
        Participant existing = roster.stream()
                .filter(participant -> participant.id().equals(participantId))
                .findFirst()
                .orElseThrow(ParticipantNotFoundException::new);

        String name = request.name() == null ? existing.name() : request.name();
        String email = request.email() == null ? existing.email() : request.email();
        ParticipantUpdateCommand command = new ParticipantUpdateCommand(request.name(), request.email(), existing);
        try {
            Participant updated = provider.updateParticipant(participantId, command);
            if (updated == null || !participantId.equals(updated.id())
                    || !name.equals(updated.name()) || !email.equals(updated.email())) {
                throw DocumentProviderException.documentFailed(false);
            }
            return updated;
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_FAILED".equals(failure.code())) {
                throw failure;
            }
            throw DocumentProviderException.documentFailed(failure.retryable(), failure);
        } catch (RuntimeException providerFailure) {
            throw DocumentProviderException.documentFailed(false, providerFailure);
        }
    }
}
