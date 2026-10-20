package com.meetingautomation.document;

/** Capability for removing a participant page from the active roster. */
public interface ParticipantDeletingProvider extends ParticipantListingProvider {
    void deleteParticipant(String participantsPageId, Participant participant);
}
