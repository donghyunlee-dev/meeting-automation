package com.meetingautomation.document.transfer;

import com.meetingautomation.document.*;
import java.util.*;

/** Shared provider validation and verified create. Transport remains provider-specific. */
public abstract class ProviderTransferSupport implements DocumentTransferPort {
    protected final TransferCodec codec = new TransferCodec();
    protected final TransferPageCodec pages = new TransferPageCodec();
    protected abstract String fingerprint();
    protected abstract DocumentStructure structure();
    protected abstract List<String> children(String root);
    protected abstract TransferRecord read(String id);
    protected abstract String create(String parent, TransferRecord record);

    @Override public TransferManifest readManifest() {
        DocumentStructure structure = structure(); List<TransferRecord> participants = new ArrayList<>(); List<TransferRecord> meetings = new ArrayList<>();
        Set<String> ids = new HashSet<>(); Set<String> participantIds = new HashSet<>(); Set<String> sessions = new HashSet<>(); Set<String> keys = new HashSet<>();
        for (String id : children(structure.participantsPageId())) { if (!ids.add(id)) throw invalid(); TransferRecord p = read(id); if (p.participant() == null) throw invalid(); participants.add(p); participantIds.add(id); validateTransfer(p, keys); }
        for (String id : children(structure.meetingsPageId())) { if (!ids.add(id)) throw invalid(); TransferRecord m = read(id); if (m.participant() != null || !sessions.add(m.marker().externalSessionId())) throw invalid(); TransferPageCodec.validateReferences(m, participantIds); meetings.add(m); validateTransfer(m, keys); }
        String fingerprint = fingerprint();
        return new TransferManifest(fingerprint, participants, meetings.stream().map(m -> new TransferManifest.Entry(m.sourceDocumentId(), m.sourceRevision(), codec.canonicalDigest(m),
                m.marker().transfer() == null ? codec.transferKey(fingerprint, m.sourceDocumentId()) : m.marker().transfer().transferKey(), m.marker().externalSessionId(), m.marker().participantIds())).toList());
    }
    @Override public TransferRecord exportMeeting(String sourceDocumentId) { TransferRecord r = read(sourceDocumentId); if (r.participant() != null) throw invalid(); return r; }
    @Override public Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint) {
        if (record.participant() == null) throw invalid(); TransferRecord target = target(record, Map.of(), sourceConnectionFingerprint);
        TransferRecord existing = find(target); if (existing != null) return existing.participant();
        return verifiedCreate(structure().participantsPageId(), target).participant();
    }
    @Override public MeetingDocumentRef importMeeting(TransferRecord record, Map<String,String> participantIdMap, String sourceConnectionFingerprint) {
        if (record.participant() != null) throw invalid(); TransferRecord target = target(record, participantIdMap, sourceConnectionFingerprint);
        TransferRecord existing = find(target); TransferRecord result = existing == null ? verifiedCreate(structure().meetingsPageId(), target) : existing;
        return new MeetingDocumentRef(result.sourceDocumentId(), result.documentUrl());
    }
    private void validateTransfer(TransferRecord r, Set<String> keys) {
        var t = r.marker().transfer(); if (t != null && (!keys.add(t.transferKey()) || !codec.canonicalDigest(r).equals(t.canonicalDigest())
                || !codec.transferKey(t.sourceConnectionFingerprint(), t.sourceDocumentId()).equals(t.transferKey()))) throw conflict();
    }
    private TransferRecord find(TransferRecord target) {
        TransferManifest manifest = readManifest(); String key = target.marker().transfer().transferKey();
        TransferPageCodec.validateReferences(target, new HashSet<>(manifest.participants().stream().map(p -> p.participant().id()).toList()));
        for (TransferRecord p : manifest.participants()) if (p.marker().transfer() != null && p.marker().transfer().transferKey().equals(key)) { same(target, p); return p; }
        for (var entry : manifest.meetings()) {
            if (entry.transferKey().equals(key)) { TransferRecord m = read(entry.sourceDocumentId()); same(target, m); return m; }
            if (target.participant() == null && entry.externalSessionId().equals(target.marker().externalSessionId())) throw conflict();
        }
        return null;
    }
    private TransferRecord verifiedCreate(String parent, TransferRecord record) {
        String id;
        try { id = create(parent, record); } catch (RuntimeException ambiguous) {
            try { TransferRecord reconciled = find(record); if (reconciled != null) return reconciled; }
            catch (TransferAdapterException conflict) { if (conflict.code() == TransferAdapterException.Code.CONFLICT) throw conflict; }
            catch (RuntimeException unverified) { /* Outcome remains unknown. */ }
            throw new TransferAdapterException(TransferAdapterException.Code.RECONCILIATION_REQUIRED);
        }
        try { TransferRecord actual = read(id); same(record, actual); return actual; }
        catch (RuntimeException mismatch) {
            if (mismatch instanceof TransferAdapterException e && e.code() == TransferAdapterException.Code.CONFLICT) throw e;
            throw new TransferAdapterException(TransferAdapterException.Code.RECONCILIATION_REQUIRED);
        }
    }
    private void same(TransferRecord expected, TransferRecord actual) {
        validateTransfer(actual, new HashSet<>());
        if (actual.marker().transfer() == null || !actual.marker().transfer().transferKey().equals(expected.marker().transfer().transferKey())
                || !codec.canonicalDigest(expected).equals(codec.canonicalDigest(actual))) throw conflict();
    }
    private TransferRecord target(TransferRecord r, Map<String,String> map, String fingerprint) {
        String digest = codec.canonicalDigest(r); var m = r.marker();
        java.util.function.Function<String,String> mapped = id -> { if (id == null) return null; String value = map.get(id); if (value == null || value.isBlank()) throw new TransferCodec.ValidationException(TransferCodec.Code.BROKEN_REFERENCE); return value; };
        var transfer = new TransferMarker.Transfer(codec.transferKey(fingerprint, r.sourceDocumentId()), fingerprint, r.sourceDocumentId(), r.sourceRevision(), digest, map);
        var marker = new TransferMarker(m.format(), m.recordType(), m.schemaVersion(), m.externalSessionId(), m.meetingAt(), m.templateId(), m.participantIds().stream().map(mapped).toList(),
                m.speakers().stream().map(s -> new TransferMarker.Speaker(s.speakerId(), s.label(), mapped.apply(s.participantId()))).toList(), m.failure(), r.participant() == null ? null : digest, transfer);
        StructuredMinutes minutes = r.minutes();
        if (minutes != null) minutes = new StructuredMinutes(minutes.templateId(), minutes.templateVersion(), minutes.summary(), minutes.discussionPoints(), minutes.decisions(),
                minutes.actionItems().stream().map(a -> new StructuredMinutes.ActionItem(mapped.apply(a.ownerParticipantId()), a.task(), a.dueDate())).toList(), minutes.followUps());
        return new TransferRecord(r.sourceDocumentId(), r.sourceRevision(), r.documentUrl(), marker, r.participant() == null ? r.title() : r.participant().name(), minutes, r.transcript(),
                r.participants().stream().map(p -> new ParticipantSummary(mapped.apply(p.id()), p.name())).toList(), r.participant());
    }
    private static TransferCodec.ValidationException invalid() { return new TransferCodec.ValidationException(TransferCodec.Code.INVALID_RECORD); }
    private static TransferAdapterException conflict() { return new TransferAdapterException(TransferAdapterException.Code.CONFLICT); }
}
