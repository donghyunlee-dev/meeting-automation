package com.meetingautomation.document.transfer;

import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.Participant;
import java.util.Map;

public interface DocumentTransferPort {
    TransferManifest readManifest();
    TransferRecord exportMeeting(String sourceDocumentId);
    Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint);
    MeetingDocumentRef importMeeting(TransferRecord record, Map<String, String> participantIdMap,
            String sourceConnectionFingerprint);
}
