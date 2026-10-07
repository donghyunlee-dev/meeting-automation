package com.meetingautomation.document;

import java.util.List;

/** Read-only provider capability for listing the roster under a Participants page. */
public interface ParticipantListingProvider extends DocumentStructureProvider {
    List<Participant> listParticipants(String participantsPageId);
}
