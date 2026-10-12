package com.meetingautomation.document.transfer;

import java.util.List;

/** Complete participant payloads and lightweight meeting entries from exhausted provider pagination. */
public record TransferManifest(String sourceConnectionFingerprint, List<TransferRecord> participants,
        List<Entry> meetings) {
    public TransferManifest {
        participants = List.copyOf(participants);
        meetings = List.copyOf(meetings);
    }

    public record Entry(String sourceDocumentId, String sourceRevision, String canonicalDigest,
            String transferKey, String externalSessionId, List<String> participantIds) {
        public Entry { participantIds = List.copyOf(participantIds); }
    }
}
