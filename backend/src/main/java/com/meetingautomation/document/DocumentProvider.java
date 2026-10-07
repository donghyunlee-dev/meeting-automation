package com.meetingautomation.document;

import java.util.List;
import java.util.Optional;

/** Provider-neutral boundary for document storage. */
public interface DocumentProvider {
    ProviderHealth validateConnection();

    DocumentStructure discoverStructure(String rootId);

    List<MeetingSummary> listMeetings(int max);

    default List<MeetingSummary> listMeetings() {
        return listMeetings(100);
    }

    MeetingDocument getMeeting(String documentId);

    Optional<MeetingDocumentRef> findMeetingBySessionId(String sessionId);

    MeetingDocumentRef createMeeting(CreateMeetingCommand command);

    List<Participant> listParticipants();

    Participant createParticipant(CreateParticipantCommand command);

    Participant updateParticipant(String participantId, UpdateParticipantCommand command);
}
