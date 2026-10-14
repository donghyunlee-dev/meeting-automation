package com.meetingautomation.document.transfer;

import com.meetingautomation.document.Participant;
import com.meetingautomation.document.MeetingDocumentRef;
import java.util.List;
import java.util.Map;

public interface DocumentTransferPort {
    TransferManifest readManifest();
    /** Returns decoded history meeting records with repeated meeting child IDs collapsed. */
    default List<TransferRecord> listMeetingRecords() {
        throw new UnsupportedOperationException("History meeting listing is not implemented by this provider.");
    }
    TransferRecord exportMeeting(String sourceDocumentId);
    Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint);
    MeetingDocumentRef importMeeting(TransferRecord record, Map<String, String> participantIdMap,
            String sourceConnectionFingerprint);

    /** Publishes a new native Meeting record or reuses the verified record with the same external Session ID. */
    MeetingDocumentRef publishMeeting(TransferRecord meeting);
}
