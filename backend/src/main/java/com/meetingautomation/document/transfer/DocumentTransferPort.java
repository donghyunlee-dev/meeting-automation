package com.meetingautomation.document.transfer;

import com.meetingautomation.document.Participant;
import com.meetingautomation.document.MeetingDocumentRef;
import java.util.Map;

public interface DocumentTransferPort {
    TransferManifest readManifest();
    TransferRecord exportMeeting(String sourceDocumentId);
    Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint);
    MeetingDocumentRef importMeeting(TransferRecord record, Map<String, String> participantIdMap,
            String sourceConnectionFingerprint);

    /** Publishes a new native Meeting record or reuses the verified record with the same external Session ID. */
    MeetingDocumentRef publishMeeting(TransferRecord meeting);
}
