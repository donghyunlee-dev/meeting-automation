package com.meetingautomation.document.transfer;

import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.StructuredMinutes;
import java.util.*;

/** Validates the complete transfer before writes, then copies participants followed by meetings. */
public final class DocumentTransferService {
    private final TransferCodec codec;

    public DocumentTransferService() { this(new TransferCodec()); }
    DocumentTransferService(TransferCodec codec) { this.codec = Objects.requireNonNull(codec); }

    public TransferResult transfer(DocumentTransferPort source, DocumentTransferPort target) {
        Objects.requireNonNull(source); Objects.requireNonNull(target);
        TransferManifest sourceManifest = source.readManifest();
        SourceSnapshot snapshot = validateSource(source, sourceManifest);
        TransferManifest targetBefore = target.readManifest();
        TargetSnapshot targetSnapshot = validateTarget(target, targetBefore, snapshot, sourceManifest.sourceConnectionFingerprint());

        Map<String, String> idMap = new LinkedHashMap<>();
        int participantsCopied = 0;
        int participantsReused = 0;
        for (TransferRecord sourceParticipant : snapshot.participants()) {
            String key = sourceKey(sourceManifest.sourceConnectionFingerprint(), sourceParticipant.sourceDocumentId());
            TransferRecord existing = targetSnapshot.participantsByKey().get(key);
            if (existing != null) {
                idMap.put(sourceParticipant.participant().id(), existing.participant().id());
                participantsReused++;
                continue;
            }
            Participant imported;
            try {
                imported = target.importParticipant(sourceParticipant, sourceManifest.sourceConnectionFingerprint());
            } catch (RuntimeException failure) {
                throw translate(failure);
            }
            if (imported == null || imported.id() == null || imported.id().isBlank()
                    || !Objects.equals(imported.name(), sourceParticipant.participant().name())
                    || !Objects.equals(imported.email(), sourceParticipant.participant().email())) {
                throw conflict(TransferConflictException.Code.INVALID_IMPORT_RESULT);
            }
            idMap.put(sourceParticipant.participant().id(), imported.id());
            participantsCopied++;
        }
        validateCompleteMap(snapshot.participants(), idMap);
        TransferManifest targetAfterParticipants = readTarget(target);
        Map<String, TransferRecord> targetParticipantsByKey = validateTargetParticipants(targetAfterParticipants);
        for (TransferRecord sourceParticipant : snapshot.participants()) {
            String key = sourceKey(sourceManifest.sourceConnectionFingerprint(), sourceParticipant.sourceDocumentId());
            TransferRecord actual = targetParticipantsByKey.get(key);
            String mappedId = idMap.get(sourceParticipant.participant().id());
            if (actual == null || !mappedId.equals(actual.participant().id())
                    || !sameParticipant(sourceParticipant, actual)) {
                throw conflict(TransferConflictException.Code.RECONCILIATION_REQUIRED);
            }
        }

        Map<String, String> targetMeetingIds = new LinkedHashMap<>();
        int meetingsCopied = 0;
        int meetingsReused = 0;
        for (TransferRecord sourceMeeting : snapshot.meetings()) {
            String key = sourceKey(sourceManifest.sourceConnectionFingerprint(), sourceMeeting.sourceDocumentId());
            TransferRecord existing = targetSnapshot.meetingsByKey().get(key);
            if (existing != null) {
                targetMeetingIds.put(sourceMeeting.sourceDocumentId(), existing.sourceDocumentId());
                meetingsReused++;
                continue;
            }
            MeetingDocumentRef imported;
            try {
                imported = target.importMeeting(sourceMeeting, idMap, sourceManifest.sourceConnectionFingerprint());
            } catch (RuntimeException failure) {
                throw translate(failure);
            }
            if (imported == null || imported.documentId() == null || imported.documentId().isBlank()) {
                throw conflict(TransferConflictException.Code.RECONCILIATION_REQUIRED);
            }
            targetMeetingIds.put(sourceMeeting.sourceDocumentId(), imported.documentId());
            meetingsCopied++;
        }

        TransferManifest targetAfter = readTarget(target);
        verifyTargetMeetings(target, targetAfter, snapshot, sourceManifest.sourceConnectionFingerprint(), idMap, targetMeetingIds);
        verifySourceUnchanged(source, snapshot, sourceManifest.sourceConnectionFingerprint());
        return new TransferResult(sourceManifest.sourceConnectionFingerprint(), targetAfter.sourceConnectionFingerprint(),
                participantsCopied, participantsReused, meetingsCopied, meetingsReused, idMap,
                new ArrayList<>(targetMeetingIds.values()));
    }

    private SourceSnapshot validateSource(DocumentTransferPort source, TransferManifest manifest) {
        requireManifest(manifest);
        requireFingerprint(manifest.sourceConnectionFingerprint());
        Map<String, TransferRecord> participantsById = new LinkedHashMap<>();
        Set<String> transferKeys = new HashSet<>();
        for (TransferRecord participant : manifest.participants()) {
            validateRecordIdentity(participant);
            if (participant.participant() == null || !"participant".equals(participant.marker().recordType())
                    || !participant.sourceDocumentId().equals(participant.participant().id())) invalid(TransferCodec.Code.INVALID_RECORD);
            if (participantsById.putIfAbsent(participant.sourceDocumentId(), participant) != null) invalid(TransferCodec.Code.INVALID_IDENTITY);
            String digest = safeDigest(participant);
            if (!digest.equals(participant.marker().canonicalDigest())) invalid(TransferCodec.Code.INVALID_RECORD);
            addKey(transferKeys, sourceKey(manifest.sourceConnectionFingerprint(), participant.sourceDocumentId()));
            if (participant.marker().transfer() != null) {
                var transfer = participant.marker().transfer();
                if (!sourceKey(manifest.sourceConnectionFingerprint(), participant.sourceDocumentId()).equals(transfer.transferKey())) addKey(transferKeys, transfer.transferKey());
                if (!digest.equals(transfer.canonicalDigest())) invalid(TransferCodec.Code.INVALID_RECORD);
            }
        }
        Set<String> meetingIds = new HashSet<>();
        Set<String> externalIds = new HashSet<>();
        List<TransferRecord> meetings = new ArrayList<>();
        Map<String, TransferManifest.Entry> entries = new HashMap<>();
        for (TransferManifest.Entry entry : manifest.meetings()) {
            if (entry == null || blank(entry.sourceDocumentId()) || blank(entry.sourceRevision())
                    || blank(entry.externalSessionId()) || !meetingIds.add(entry.sourceDocumentId())) invalid(TransferCodec.Code.INVALID_RECORD);
            if (!externalIds.add(entry.externalSessionId())) throw conflict(TransferConflictException.Code.DUPLICATE_EXTERNAL_SESSION_ID);
            if (!entry.canonicalDigest().matches("sha256:[0-9a-f]{64}")) invalid(TransferCodec.Code.INVALID_RECORD);
            addKey(transferKeys, entry.transferKey());
            String localKey = sourceKey(manifest.sourceConnectionFingerprint(), entry.sourceDocumentId());
            if (!localKey.equals(entry.transferKey())) addKey(transferKeys, localKey);
            entries.put(entry.sourceDocumentId(), entry);
        }
        for (TransferManifest.Entry entry : manifest.meetings()) {
            TransferRecord meeting = source.exportMeeting(entry.sourceDocumentId());
            validateMeetingAgainstEntry(meeting, entry, participantsById.keySet(), manifest.sourceConnectionFingerprint());
            meetings.add(meeting);
        }
        return new SourceSnapshot(manifest.sourceConnectionFingerprint(), List.copyOf(manifest.participants()), List.copyOf(meetings), Map.copyOf(entries));
    }

    private void validateMeetingAgainstEntry(TransferRecord meeting, TransferManifest.Entry entry, Set<String> participantIds, String sourceFingerprint) {
        validateRecordIdentity(meeting);
        if (meeting.participant() != null || !"meeting".equals(meeting.marker().recordType())
                || !entry.sourceDocumentId().equals(meeting.sourceDocumentId())
                || !entry.sourceRevision().equals(meeting.sourceRevision())
                || !entry.externalSessionId().equals(meeting.marker().externalSessionId())
                || !entry.participantIds().equals(meeting.marker().participantIds())
                || !entry.canonicalDigest().equals(safeDigest(meeting))) invalid(TransferCodec.Code.INVALID_RECORD);
        if (meeting.marker().transfer() == null) {
            if (!sourceKey(sourceFingerprint, entry.sourceDocumentId()).equals(entry.transferKey())) throw conflict(TransferConflictException.Code.TRANSFER_KEY_MISMATCH);
        } else if (!meeting.marker().transfer().transferKey().equals(entry.transferKey())
                || !meeting.marker().transfer().canonicalDigest().equals(safeDigest(meeting))) {
            throw conflict(TransferConflictException.Code.TRANSFER_KEY_MISMATCH);
        }
        TransferPageCodec.validateReferences(meeting, participantIds);
    }

    private TargetSnapshot validateTarget(DocumentTransferPort target, TransferManifest manifest, SourceSnapshot source, String sourceFingerprint) {
        requireManifest(manifest);
        requireFingerprint(manifest.sourceConnectionFingerprint());
        Map<String, TransferRecord> participantByKey = validateTargetParticipants(manifest);
        for (TransferRecord sourceParticipant : source.participants()) {
            String key = sourceKey(sourceFingerprint, sourceParticipant.sourceDocumentId());
            TransferRecord existing = participantByKey.get(key);
            if (existing != null && !safeDigest(sourceParticipant).equals(safeDigest(existing))) {
                throw conflict(TransferConflictException.Code.DIGEST_MISMATCH);
            }
            if (sourceFingerprint.equals(manifest.sourceConnectionFingerprint())) {
                TransferRecord sameId = manifest.participants().stream()
                        .filter(p -> p.sourceDocumentId().equals(sourceParticipant.sourceDocumentId())).findFirst().orElse(null);
                if (sameId != null && existing == null) {
                    throw conflict(sameId.marker().transfer() == null
                            ? TransferConflictException.Code.UNMARKED_COLLISION : TransferConflictException.Code.TRANSFER_KEY_MISMATCH);
                }
            }
        }
        Map<String, TransferRecord> meetingByKey = new HashMap<>();
        Map<String, TransferRecord> targetMeetingsById = new HashMap<>();
        Set<String> targetKeys = new HashSet<>(participantByKey.keySet());
        Set<String> targetSessions = new HashSet<>();
        for (TransferManifest.Entry entry : manifest.meetings()) {
            if (!targetSessions.add(entry.externalSessionId())) throw conflict(TransferConflictException.Code.DUPLICATE_EXTERNAL_SESSION_ID);
            TransferRecord record = target.exportMeeting(entry.sourceDocumentId());
            validateMeetingAgainstTargetEntry(record, entry, manifest);
            targetMeetingsById.put(record.sourceDocumentId(), record);
            if (record.marker().transfer() != null) {
                String key = record.marker().transfer().transferKey();
                addKey(targetKeys, key);
                TransferRecord previous = meetingByKey.putIfAbsent(key, record);
                if (previous != null) throw conflict(TransferConflictException.Code.DUPLICATE_TRANSFER_KEY);
            }
        }
        if (sourceFingerprint.equals(manifest.sourceConnectionFingerprint())) {
            for (TransferManifest.Entry sourceEntry : source.entries().values()) {
                TransferRecord sameId = targetMeetingsById.get(sourceEntry.sourceDocumentId());
                if (sameId != null && !sourceKey(sourceFingerprint, sourceEntry.sourceDocumentId())
                        .equals(collisionTransferKey(sameId))) {
                    throw conflict(sameId.marker().transfer() == null
                            ? TransferConflictException.Code.UNMARKED_COLLISION : TransferConflictException.Code.TRANSFER_KEY_MISMATCH);
                }
            }
        }
        for (TransferManifest.Entry sourceEntry : source.entries().values()) {
            String expectedKey = sourceKey(sourceFingerprint, sourceEntry.sourceDocumentId());
            TransferRecord collision = manifest.meetings().stream()
                    .filter(e -> e.externalSessionId().equals(sourceEntry.externalSessionId()))
                    .map(e -> meetingByKey.get(e.transferKey())).filter(Objects::nonNull).findFirst().orElse(null);
            if (collision != null && !expectedKey.equals(collisionTransferKey(collision))) {
                throw conflict(collision.marker().transfer() == null
                        ? TransferConflictException.Code.UNMARKED_COLLISION : TransferConflictException.Code.TRANSFER_KEY_MISMATCH);
            }
        }
        for (TransferManifest.Entry entry : source.entries().values()) {
            TransferRecord match = meetingByKey.get(sourceKey(sourceFingerprint, entry.sourceDocumentId()));
            if (match != null && !entry.canonicalDigest().equals(safeDigest(match))) throw conflict(TransferConflictException.Code.DIGEST_MISMATCH);
            if (match == null && manifest.meetings().stream().anyMatch(t -> t.externalSessionId().equals(entry.externalSessionId()))) {
                throw conflict(TransferConflictException.Code.UNMARKED_COLLISION);
            }
            if (match != null) validateReusedMeetingParticipantMap(match, source, participantByKey, sourceFingerprint);
        }
        return new TargetSnapshot(participantByKey, meetingByKey);
    }

    private void validateReusedMeetingParticipantMap(TransferRecord meeting, SourceSnapshot source,
            Map<String, TransferRecord> targetParticipantsByKey, String sourceFingerprint) {
        var transfer = meeting.marker().transfer();
        if (transfer == null) throw conflict(TransferConflictException.Code.UNMARKED_COLLISION);
        TransferRecord sourceMeeting = source.meetings().stream()
                .filter(candidate -> candidate.sourceDocumentId().equals(transfer.sourceDocumentId()))
                .findFirst().orElseThrow(() -> conflict(TransferConflictException.Code.PARTICIPANT_MAPPING_MISMATCH));
        Map<String, String> currentMap = new LinkedHashMap<>();
        for (TransferRecord participant : source.participants()) {
            TransferRecord targetParticipant = targetParticipantsByKey.get(sourceKey(sourceFingerprint, participant.sourceDocumentId()));
            if (targetParticipant == null) throw conflict(TransferConflictException.Code.PARTICIPANT_MAPPING_MISMATCH);
            currentMap.put(participant.participant().id(), targetParticipant.participant().id());
        }
        Map<String, String> expectedMap = expectedParticipantMapping(sourceMeeting, currentMap);
        if (expectedMap.values().stream().anyMatch(DocumentTransferService::blank)
                || new HashSet<>(expectedMap.values()).size() != expectedMap.size()
                || !expectedMap.equals(transfer.participantIdMap())) {
            throw conflict(TransferConflictException.Code.PARTICIPANT_MAPPING_MISMATCH);
        }
    }

    private Map<String, TransferRecord> validateTargetParticipants(TransferManifest manifest) {
        Map<String, TransferRecord> byKey = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (TransferRecord participant : manifest.participants()) {
            validateRecordIdentity(participant);
            if (participant.participant() == null || !"participant".equals(participant.marker().recordType())
                    || !participant.sourceDocumentId().equals(participant.participant().id()) || !ids.add(participant.sourceDocumentId())) invalid(TransferCodec.Code.INVALID_RECORD);
            var transfer = participant.marker().transfer();
            if (!safeDigest(participant).equals(participant.marker().canonicalDigest())) {
                if (transfer == null) invalid(TransferCodec.Code.INVALID_RECORD);
                throw conflict(TransferConflictException.Code.DIGEST_MISMATCH);
            }
            if (transfer != null) {
                if (!codec.transferKey(transfer.sourceConnectionFingerprint(), transfer.sourceDocumentId()).equals(transfer.transferKey())) invalid(TransferCodec.Code.INVALID_RECORD);
                if (!safeDigest(participant).equals(transfer.canonicalDigest())) throw conflict(TransferConflictException.Code.DIGEST_MISMATCH);
                if (byKey.putIfAbsent(transfer.transferKey(), participant) != null) throw conflict(TransferConflictException.Code.DUPLICATE_TRANSFER_KEY);
            }
        }
        return byKey;
    }

    private void validateMeetingAgainstTargetEntry(TransferRecord record, TransferManifest.Entry entry, TransferManifest manifest) {
        validateRecordIdentity(record);
        if (record.participant() != null || !"meeting".equals(record.marker().recordType())
                || !entry.sourceDocumentId().equals(record.sourceDocumentId())
                || !entry.sourceRevision().equals(record.sourceRevision())
                || !entry.externalSessionId().equals(record.marker().externalSessionId())
                || !entry.participantIds().equals(record.marker().participantIds())
                || !entry.canonicalDigest().equals(safeDigest(record))) invalid(TransferCodec.Code.INVALID_RECORD);
        Set<String> participantIds = new HashSet<>();
        manifest.participants().forEach(p -> participantIds.add(p.sourceDocumentId()));
        TransferPageCodec.validateReferences(record, participantIds);
        var transfer = record.marker().transfer();
        if (transfer != null && (!codec.transferKey(transfer.sourceConnectionFingerprint(), transfer.sourceDocumentId()).equals(transfer.transferKey())
                || !safeDigest(record).equals(transfer.canonicalDigest()))) invalid(TransferCodec.Code.INVALID_RECORD);
        String expectedEntryKey = transfer == null ? sourceKey(manifest.sourceConnectionFingerprint(), record.sourceDocumentId()) : transfer.transferKey();
        if (!expectedEntryKey.equals(entry.transferKey())) throw conflict(TransferConflictException.Code.TRANSFER_KEY_MISMATCH);
    }

    private void verifyTargetMeetings(DocumentTransferPort target, TransferManifest manifest, SourceSnapshot snapshot,
            String sourceFingerprint, Map<String, String> idMap, Map<String, String> targetIds) {
        Map<String, TransferRecord> actualByKey = new HashMap<>();
        Set<String> allKeys = new HashSet<>(validateTargetParticipants(manifest).keySet());
        Set<String> sessions = new HashSet<>();
        Set<String> documentIds = new HashSet<>();
        for (TransferManifest.Entry entry : manifest.meetings()) {
            if (!documentIds.add(entry.sourceDocumentId()) || !sessions.add(entry.externalSessionId())) {
                throw conflict(TransferConflictException.Code.RECONCILIATION_REQUIRED);
            }
            TransferRecord actual = target.exportMeeting(entry.sourceDocumentId());
            try { validateMeetingAgainstTargetEntry(actual, entry, manifest); }
            catch (TransferCodec.ValidationException malformed) { throw conflict(TransferConflictException.Code.RECONCILIATION_REQUIRED); }
            var transfer = actual.marker().transfer();
            if (transfer != null) {
                addKey(allKeys, transfer.transferKey());
                if (actualByKey.putIfAbsent(transfer.transferKey(), actual) != null) throw conflict(TransferConflictException.Code.DUPLICATE_TRANSFER_KEY);
            }
        }
        for (TransferRecord expected : snapshot.meetings()) {
            String key = sourceKey(sourceFingerprint, expected.sourceDocumentId());
            TransferRecord actual = actualByKey.get(key);
            String targetId = targetIds.get(expected.sourceDocumentId());
            if (actual == null || targetId == null || !targetId.equals(actual.sourceDocumentId())
                    || !safeDigest(expected).equals(safeDigest(actual))) throw conflict(TransferConflictException.Code.RECONCILIATION_REQUIRED);
            var transfer = actual.marker().transfer();
            if (transfer == null || !sourceFingerprint.equals(transfer.sourceConnectionFingerprint())
                    || !expected.sourceDocumentId().equals(transfer.sourceDocumentId())
                    || !safeDigest(expected).equals(transfer.canonicalDigest())
                    || !expectedParticipantMapping(expected, idMap).equals(transfer.participantIdMap())) {
                throw conflict(TransferConflictException.Code.RECONCILIATION_REQUIRED);
            }
        }
    }

    private Map<String, String> expectedParticipantMapping(TransferRecord meeting, Map<String, String> currentMap) {
        var previousTransfer = meeting.marker().transfer();
        if (previousTransfer == null || previousTransfer.participantIdMap().isEmpty()) return currentMap;
        Map<String, String> composed = new LinkedHashMap<>();
        previousTransfer.participantIdMap().forEach((canonicalId, sourceId) -> {
            String targetId = currentMap.get(sourceId);
            if (blank(targetId)) throw conflict(TransferConflictException.Code.PARTICIPANT_MAPPING_MISMATCH);
            composed.put(canonicalId, targetId);
        });
        return Map.copyOf(composed);
    }

    private void verifySourceUnchanged(DocumentTransferPort source, SourceSnapshot snapshot, String fingerprint) {
        SourceSnapshot after;
        try { after = validateSource(source, source.readManifest()); }
        catch (RuntimeException unreadable) { throw conflict(TransferConflictException.Code.SOURCE_CHANGED); }
        if (!Objects.equals(fingerprint, after.fingerprint())
                || after.participants().size() != snapshot.participants().size()
                || after.meetings().size() != snapshot.meetings().size()) throw conflict(TransferConflictException.Code.SOURCE_CHANGED);
        Map<String, TransferRecord> participants = new HashMap<>();
        for (TransferRecord p : after.participants()) participants.put(p.sourceDocumentId(), p);
        for (TransferRecord before : snapshot.participants()) {
            TransferRecord current = participants.get(before.sourceDocumentId());
            if (current == null || !Objects.equals(before.sourceRevision(), current.sourceRevision())
                    || !safeDigest(before).equals(safeDigest(current))) throw conflict(TransferConflictException.Code.SOURCE_CHANGED);
        }
        Map<String, TransferManifest.Entry> entries = after.entries();
        Map<String, TransferRecord> currentMeetings = new HashMap<>();
        for (TransferRecord meeting : after.meetings()) currentMeetings.put(meeting.sourceDocumentId(), meeting);
        for (TransferRecord before : snapshot.meetings()) {
            TransferManifest.Entry entry = entries.get(before.sourceDocumentId());
            TransferManifest.Entry originalEntry = snapshot.entries().get(before.sourceDocumentId());
            if (entry == null || !Objects.equals(before.sourceRevision(), entry.sourceRevision())
                    || !safeDigest(before).equals(entry.canonicalDigest())
                    || originalEntry == null || !Objects.equals(originalEntry.transferKey(), entry.transferKey())
                    || !Objects.equals(before.marker().externalSessionId(), entry.externalSessionId())
                    || !before.marker().participantIds().equals(entry.participantIds())) throw conflict(TransferConflictException.Code.SOURCE_CHANGED);
            TransferRecord current = currentMeetings.get(before.sourceDocumentId());
            if (current == null || !Objects.equals(before.sourceRevision(), current.sourceRevision())
                    || !safeDigest(before).equals(safeDigest(current))) throw conflict(TransferConflictException.Code.SOURCE_CHANGED);
        }
    }

    private String collisionTransferKey(TransferRecord record) {
        return record.marker().transfer() == null ? null : record.marker().transfer().transferKey();
    }
    private String sourceKey(String fingerprint, String id) { return codec.transferKey(fingerprint, id); }
    private boolean sameParticipant(TransferRecord expected, TransferRecord actual) {
        return Objects.equals(expected.participant().name(), actual.participant().name())
                && Objects.equals(expected.participant().email(), actual.participant().email())
                && safeDigest(expected).equals(safeDigest(actual));
    }
    private void validateCompleteMap(List<TransferRecord> participants, Map<String, String> idMap) {
        if (idMap.size() != participants.size() || idMap.values().stream().anyMatch(DocumentTransferService::blank)
                || new HashSet<>(idMap.values()).size() != idMap.size()) invalid(TransferCodec.Code.BROKEN_REFERENCE);
        for (TransferRecord participant : participants) if (!idMap.containsKey(participant.participant().id())) invalid(TransferCodec.Code.BROKEN_REFERENCE);
    }
    private void validateRecordIdentity(TransferRecord record) {
        if (record == null || blank(record.sourceDocumentId()) || blank(record.sourceRevision()) || record.marker() == null
                || !"meeting-automation".equals(record.marker().format()) || !"1.0".equals(record.marker().schemaVersion())) invalid(TransferCodec.Code.INVALID_RECORD);
        if (record.marker().transfer() != null) {
            var t = record.marker().transfer();
            if (!codec.transferKey(t.sourceConnectionFingerprint(), t.sourceDocumentId()).equals(t.transferKey())) invalid(TransferCodec.Code.INVALID_RECORD);
        }
    }
    private String safeDigest(TransferRecord record) { return codec.canonicalDigest(record); }
    private TransferManifest readTarget(DocumentTransferPort target) {
        try { return target.readManifest(); }
        catch (RuntimeException failure) { throw conflict(TransferConflictException.Code.RECONCILIATION_REQUIRED); }
    }
    private void requireManifest(TransferManifest manifest) {
        if (manifest == null || manifest.participants() == null || manifest.meetings() == null) invalid(TransferCodec.Code.INVALID_RECORD);
    }
    private void requireFingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("sha256:[0-9a-f]{64}")) invalid(TransferCodec.Code.INVALID_IDENTITY);
    }
    private void addKey(Set<String> keys, String key) {
        if (!keys.add(key)) throw conflict(TransferConflictException.Code.DUPLICATE_TRANSFER_KEY);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void invalid(TransferCodec.Code code) { throw new TransferCodec.ValidationException(code); }
    private static TransferConflictException conflict(TransferConflictException.Code code) { return new TransferConflictException(code); }
    private RuntimeException translate(RuntimeException failure) {
        if (failure instanceof TransferConflictException || failure instanceof TransferCodec.ValidationException) return failure;
        if (failure instanceof TransferAdapterException adapter) {
            return conflict(adapter.code() == TransferAdapterException.Code.RECONCILIATION_REQUIRED
                    ? TransferConflictException.Code.RECONCILIATION_REQUIRED : TransferConflictException.Code.DIGEST_MISMATCH);
        }
        return conflict(TransferConflictException.Code.RECONCILIATION_REQUIRED);
    }
    private record SourceSnapshot(String fingerprint, List<TransferRecord> participants, List<TransferRecord> meetings,
            Map<String, TransferManifest.Entry> entries) { }
    private record TargetSnapshot(Map<String, TransferRecord> participantsByKey, Map<String, TransferRecord> meetingsByKey) { }
}
