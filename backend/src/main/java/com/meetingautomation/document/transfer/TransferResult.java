package com.meetingautomation.document.transfer;

import java.util.List;
import java.util.Map;

/** Content-free summary; no meeting body, credentials, or provider URLs are retained. */
public record TransferResult(String sourceManifestFingerprint, String targetManifestFingerprint,
        int participantsCopied, int participantsReused, int meetingsCopied, int meetingsReused,
        Map<String, String> participantIdMap, List<String> targetMeetingIds) {
    public TransferResult {
        participantIdMap = Map.copyOf(participantIdMap);
        targetMeetingIds = List.copyOf(targetMeetingIds);
    }
}
