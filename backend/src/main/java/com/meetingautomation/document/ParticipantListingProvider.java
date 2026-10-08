package com.meetingautomation.document;

import java.util.List;

/** Read-only provider capability for listing the roster under a Participants page. */
public interface ParticipantListingProvider extends DocumentStructureProvider {
    /** Discovers the roster structure while classifying failures for this read-only API operation. */
    default DocumentStructure discoverParticipantStructure(String rootId) {
        try {
            return discoverStructure(rootId);
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code())) throw failure;
            throw DocumentProviderException.participantListFailed(failure.retryable(), failure);
        }
    }

    List<Participant> listParticipants(String participantsPageId);
}
