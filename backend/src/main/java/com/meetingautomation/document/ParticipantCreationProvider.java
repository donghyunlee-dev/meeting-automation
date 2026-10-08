package com.meetingautomation.document;

/** Provider capability for creating one roster page under the Participants page. */
public interface ParticipantCreationProvider extends ParticipantListingProvider {
    /** Discovers the structure for a create preflight whose safe retry rules differ from a POST outcome. */
    default DocumentStructure discoverParticipantCreateStructure(String rootId) {
        return discoverStructure(rootId);
    }

    Participant createParticipant(String participantsPageId, CreateParticipantCommand command);
}
