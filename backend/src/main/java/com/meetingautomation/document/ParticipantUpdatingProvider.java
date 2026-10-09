package com.meetingautomation.document;

/** Incremental capability for updating a participant already in the selected roster. */
public interface ParticipantUpdatingProvider extends ParticipantListingProvider {
    Participant updateParticipant(String participantId, ParticipantUpdateCommand command);
}
