package com.meetingautomation.document.transfer;

import com.meetingautomation.document.Participant;
import java.util.List;

/** Ordinary CRUD shares the participant envelope and content digest with transfer. */
public final class ParticipantTransferMarkers {
    private static final TransferCodec CODEC = new TransferCodec();
    private ParticipantTransferMarkers() { }
    public static TransferMarker refreshed(Participant participant, TransferMarker previous) {
        if (previous != null && !previous.recordType().equals("participant")) throw new TransferCodec.ValidationException(TransferCodec.Code.INVALID_RECORD);
        var marker = new TransferMarker("meeting-automation", "participant", "1.0", null, null, null,
                List.of(), List.of(), null, "sha256:" + "0".repeat(64), previous == null ? null : previous.transfer());
        String digest = CODEC.canonicalDigest(new TransferRecord(participant.id(), null, null, marker, null, null, null, List.of(), participant));
        TransferMarker.Transfer transfer = marker.transfer();
        if (transfer != null) transfer = new TransferMarker.Transfer(transfer.transferKey(), transfer.sourceConnectionFingerprint(), transfer.sourceDocumentId(), transfer.sourceRevision(), digest, transfer.participantIdMap());
        return new TransferMarker(marker.format(), marker.recordType(), marker.schemaVersion(), null, null, null,
                List.of(), List.of(), null, digest, transfer);
    }
    public static void validateCurrent(Participant current, TransferMarker marker) {
        if (!refreshed(current, marker).canonicalDigest().equals(marker.canonicalDigest())) throw new TransferCodec.ValidationException(TransferCodec.Code.INVALID_RECORD);
    }
}
