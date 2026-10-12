package com.meetingautomation.document.transfer;

import java.util.Map;

/** Content-free transfer progress suitable for a durable operation summary. */
public record TransferProgress(String stage, int participantsTotal, int participantsCopied, int participantsReused,
        int meetingsTotal, int meetingsCopied, int meetingsReused, Map<String, String> participantIdMap) {
    public TransferProgress {
        participantIdMap = Map.copyOf(participantIdMap);
    }
}
