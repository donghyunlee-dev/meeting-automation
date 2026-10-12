package com.meetingautomation.document.transfer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Provider-neutral metadata only; human-readable content lives outside the marker. */
public record TransferMarker(String format, String recordType, String schemaVersion,
        String externalSessionId, OffsetDateTime meetingAt, String templateId,
        List<String> participantIds, List<Speaker> speakers, Failure failure,
        String canonicalDigest, Transfer transfer) {
    public TransferMarker {
        participantIds = List.copyOf(participantIds);
        speakers = List.copyOf(speakers);
    }

    public record Speaker(String speakerId, String label, String participantId) { }
    public record Failure(String stage, String errorCode, String audioDisposition) { }
    public record Transfer(String transferKey, String sourceConnectionFingerprint, String sourceDocumentId,
            String sourceRevision, String canonicalDigest, Map<String, String> participantIdMap) {
        public Transfer { participantIdMap = Map.copyOf(participantIdMap); }
    }
}
