package com.meetingautomation.document.transfer;

import com.meetingautomation.document.Participant;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.MeetingSummary;
import com.meetingautomation.document.DocumentProviderException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DocumentTransferPort {
    TransferManifest readManifest();
    /** Returns decoded history meeting records with repeated meeting child IDs collapsed. */
    default List<TransferRecord> listMeetingRecords() {
        throw new UnsupportedOperationException("History meeting listing is not implemented by this provider.");
    }
    default List<MeetingSummary> listMeetingSummaries() {
        return listMeetingRecords().stream().map(record -> {
            if (record == null || record.marker() == null || record.marker().participantIds() == null
                    || record.participants() == null) throw DocumentProviderException.documentFailed(false);
            List<String> expectedIds = record.marker().participantIds();
            Set<String> uniqueExpectedIds = new HashSet<>(expectedIds);
            Set<String> actualIds = new HashSet<>();
            for (var participant : record.participants()) {
                if (participant == null || participant.id() == null || !actualIds.add(participant.id())) {
                    throw DocumentProviderException.documentFailed(false);
                }
            }
            if (uniqueExpectedIds.size() != expectedIds.size() || !actualIds.equals(uniqueExpectedIds)) {
                throw DocumentProviderException.documentFailed(false);
            }
            return new MeetingSummary(record.sourceDocumentId(), record.title(), record.marker().meetingAt(),
                    record.participants(), record.documentUrl());
        }).toList();
    }
    default MeetingSummary findMeetingSummary(String documentId) {
        return listMeetingSummaries().stream().filter(meeting -> documentId.equals(meeting.documentId()))
                .findFirst().orElse(null);
    }
    TransferRecord exportMeeting(String sourceDocumentId);
    Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint);
    MeetingDocumentRef importMeeting(TransferRecord record, Map<String, String> participantIdMap,
            String sourceConnectionFingerprint);

    /** Publishes a new native Meeting record or reuses the verified record with the same external Session ID. */
    MeetingDocumentRef publishMeeting(TransferRecord meeting);
}
