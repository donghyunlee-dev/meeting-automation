package com.meetingautomation.document.transfer;

import com.meetingautomation.document.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared provider validation and verified create. Transport remains provider-specific. */
public abstract class ProviderTransferSupport implements DocumentTransferPort {
    private static final Logger log = LoggerFactory.getLogger(ProviderTransferSupport.class);
    protected final TransferCodec codec = new TransferCodec();
    protected final TransferPageCodec pages = new TransferPageCodec();
    protected abstract String fingerprint();
    protected abstract DocumentStructure structure();
    protected abstract List<String> children(String root);
    protected abstract TransferRecord read(String id);
    protected TransferRecord readMeeting(String id) { return read(id); }
    protected abstract String create(String parent, TransferRecord record);
    protected String meetingDataPageId() { return null; }
    protected String ensureMeetingDataPageId() { return meetingDataPageId(); }
    protected TransferRecord findMeetingByExternalSessionId(String sessionId) {
        TransferManifest manifest = readManifest();
        List<TransferManifest.Entry> matches = manifest.meetings().stream()
                .filter(entry -> sessionId.equals(entry.externalSessionId())).toList();
        if (matches.size() > 1) throw conflict();
        return matches.isEmpty() ? null : exportMeeting(matches.getFirst().sourceDocumentId());
    }
    protected void afterMeetingPublished(TransferRecord meeting) { }

    @Override public TransferManifest readManifest() {
        DocumentStructure structure = structure(); List<TransferRecord> participants = new ArrayList<>(); List<TransferRecord> meetings = new ArrayList<>();
        Set<String> ids = new HashSet<>(); Set<String> participantIds = new HashSet<>(); Set<String> sessions = new HashSet<>(); Set<String> keys = new HashSet<>();
        for (String id : children(structure.participantsPageId())) { if (!ids.add(id)) throw invalid(); TransferRecord p = read(id); if (p.participant() == null) throw invalid(); participants.add(p); participantIds.add(id); validateTransfer(p, keys); }
        for (String id : meetingRecordPages(structure)) {
            if (!ids.add(id)) throw invalid();
            TransferRecord m = read(id);
            if (m == null) continue;
            if (m.participant() != null || !sessions.add(m.marker().externalSessionId())) throw invalid();
            TransferPageCodec.validateReferences(m, participantIds); meetings.add(m); validateTransfer(m, keys);
        }
        String fingerprint = fingerprint();
        return new TransferManifest(fingerprint, participants, meetings.stream().map(m -> new TransferManifest.Entry(m.sourceDocumentId(), m.sourceRevision(), codec.canonicalDigest(m),
                m.marker().transfer() == null ? codec.transferKey(fingerprint, m.sourceDocumentId()) : m.marker().transfer().transferKey(), m.marker().externalSessionId(), m.marker().participantIds())).toList());
    }
    @Override public List<TransferRecord> listMeetingRecords() {
        DocumentStructure structure = structure();
        Set<String> participantIds = new HashSet<>();
        Set<String> meetingIds = new HashSet<>();
        Set<String> sessions = new HashSet<>();
        Set<String> keys = new HashSet<>();
        List<String> participantPages = listChildren("Participants", structure.participantsPageId());
        for (int index = 0; index < participantPages.size(); index++) {
            String id = participantPages.get(index);
            try {
                if (id == null || !participantIds.add(id)) throw invalid();
                TransferRecord participant = read(id);
                if (participant == null || participant.participant() == null) throw invalid();
                validateTransfer(participant, keys);
            } catch (RuntimeException failure) {
                log.error("Meeting history read failed: section=Participants, itemIndex={}, pageId={}, cause={}",
                        index, safePageId(id), safeCause(failure), failure);
                throw failure;
            }
        }
        List<TransferRecord> meetings = new ArrayList<>();
        List<String> meetingPages = listChildren("Meetings", structure.meetingsPageId());
        String dataPage = meetingDataPageId();
        if (dataPage != null) meetingPages = concat(meetingPages, listChildren("Meeting Data", dataPage));
        for (int index = 0; index < meetingPages.size(); index++) {
            String id = meetingPages.get(index);
            try {
                if (id == null || participantIds.contains(id)) throw invalid();
                if (!meetingIds.add(id)) continue;
                TransferRecord meeting = read(id);
                if (meeting == null) continue;
                if (meeting == null || meeting.participant() != null || meeting.marker() == null
                        || !sessions.add(meeting.marker().externalSessionId())) throw invalid();
                TransferPageCodec.validateReferences(meeting, participantIds);
                validateTransfer(meeting, keys);
                meetings.add(meeting);
            } catch (RuntimeException failure) {
                log.error("Meeting history read failed: section=Meetings, itemIndex={}, pageId={}, cause={}",
                        index, safePageId(id), safeCause(failure), failure);
                throw failure;
            }
        }
        return List.copyOf(meetings);
    }

    private List<String> listChildren(String section, String pageId) {
        try {
            return children(pageId);
        } catch (RuntimeException failure) {
            log.error("Meeting history read failed while listing section={}, pageId={}, cause={}",
                    section, safePageId(pageId), safeCause(failure), failure);
            throw failure;
        }
    }

    private List<String> meetingRecordPages(DocumentStructure structure) {
        List<String> pages = listChildren("Meetings", structure.meetingsPageId());
        String dataPage = meetingDataPageId();
        return dataPage == null ? pages : concat(pages, listChildren("Meeting Data", dataPage));
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        result.addAll(right);
        return List.copyOf(result);
    }

    private static String safePageId(String pageId) {
        return pageId == null ? "missing" : pageId.replaceAll("[^A-Za-z0-9_-]", "?");
    }

    private static String safeCause(RuntimeException failure) {
        return failure instanceof TransferCodec.ValidationException validation
                ? validation.code().name()
                : failure instanceof com.meetingautomation.document.DocumentProviderException providerFailure
                        ? providerFailure.code() : failure.getClass().getSimpleName();
    }
    @Override public TransferRecord exportMeeting(String sourceDocumentId) { TransferRecord r = readMeeting(sourceDocumentId); if (r.participant() != null) throw invalid(); return r; }
    @Override public Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint) {
        if (record.participant() == null) throw invalid(); TransferRecord target = target(record, Map.of(), sourceConnectionFingerprint);
        TransferRecord existing = find(target); if (existing != null) return existing.participant();
        return verifiedCreate(structure().participantsPageId(), target).participant();
    }
    @Override public MeetingDocumentRef importMeeting(TransferRecord record, Map<String,String> participantIdMap, String sourceConnectionFingerprint) {
        if (record.participant() != null) throw invalid(); TransferRecord target = target(record, participantIdMap, sourceConnectionFingerprint);
        TransferRecord existing = find(target);
        String dataPage = ensureMeetingDataPageId();
        TransferRecord result = existing == null ? verifiedCreate(
                dataPage == null ? structure().meetingsPageId() : dataPage, target) : existing;
        return new MeetingDocumentRef(result.sourceDocumentId(), result.documentUrl());
    }
    @Override public MeetingDocumentRef publishMeeting(TransferRecord meeting) {
        validatePublishRecord(meeting);
        TransferRecord existing = findMeetingByExternalSessionId(meeting.marker().externalSessionId());
        if (existing != null) return verifiedPublishReference(meeting, existing);

        String id;
        try {
            String dataPage = ensureMeetingDataPageId();
            id = create(dataPage == null ? structure().meetingsPageId() : dataPage, meeting);
        } catch (RuntimeException ambiguousCreate) {
            TransferRecord reconciled = reconcilePublish(meeting);
            if (reconciled != null) return verifiedPublishReference(meeting, reconciled);
            throw reconciliationRequired();
        }

        TransferRecord published;
        try {
            published = read(id);
        } catch (RuntimeException unverifiedReadback) {
            TransferRecord reconciled = reconcilePublish(meeting);
            if (reconciled != null) return verifiedPublishReference(meeting, reconciled);
            throw reconciliationRequired();
        }
        MeetingDocumentRef result = verifiedPublishReference(meeting, published);
        afterMeetingPublished(published);
        return result;
    }

    private void validatePublishRecord(TransferRecord meeting) {
        if (meeting == null || meeting.participant() != null || meeting.marker() == null
                || !"meeting-automation".equals(meeting.marker().format())
                || !"meeting".equals(meeting.marker().recordType())
                || !"1.0".equals(meeting.marker().schemaVersion())
                || meeting.marker().transfer() != null
                || meeting.marker().externalSessionId() == null || meeting.marker().externalSessionId().isBlank()
                || meeting.marker().meetingAt() == null
                || meeting.marker().templateId() == null || meeting.marker().templateId().isBlank()
                || meeting.title() == null || meeting.title().isBlank() || meeting.minutes() == null) {
            throw invalid();
        }
        try {
            TransferPageCodec.validateReferences(meeting, new HashSet<>(meeting.marker().participantIds()));
            codec.canonicalDigest(meeting);
            pages.encode(meeting);
        } catch (RuntimeException invalidRecord) {
            throw invalid();
        }
    }

    private TransferRecord reconcilePublish(TransferRecord expected) {
        try {
            TransferRecord actual = findMeetingByExternalSessionId(expected.marker().externalSessionId());
            if (actual == null) return null;
            verifiedPublishReference(expected, actual);
            return actual;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private MeetingDocumentRef verifiedPublishReference(TransferRecord expected, TransferRecord actual) {
        if (actual == null || actual.sourceDocumentId() == null || actual.sourceDocumentId().isBlank()
                || actual.documentUrl() == null || actual.documentUrl().isBlank()
                || actual.marker() == null
                || !expected.marker().externalSessionId().equals(actual.marker().externalSessionId())
                || !codec.canonicalDigest(expected).equals(codec.canonicalDigest(actual))) {
            throw conflict();
        }
        return new MeetingDocumentRef(actual.sourceDocumentId(), actual.documentUrl());
    }

    private static TransferAdapterException reconciliationRequired() {
        return new TransferAdapterException(TransferAdapterException.Code.RECONCILIATION_REQUIRED);
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
        Map<String, String> canonicalParticipantMap = new LinkedHashMap<>();
        if (m.transfer() != null && !m.transfer().participantIdMap().isEmpty()) {
            m.transfer().participantIdMap().forEach((canonicalId, sourceId) ->
                    canonicalParticipantMap.put(canonicalId, mapped.apply(sourceId)));
        } else {
            canonicalParticipantMap.putAll(map);
        }
        var transfer = new TransferMarker.Transfer(codec.transferKey(fingerprint, r.sourceDocumentId()), fingerprint,
                r.sourceDocumentId(), r.sourceRevision(), digest, canonicalParticipantMap);
        var marker = new TransferMarker(m.format(), m.recordType(), m.schemaVersion(), m.externalSessionId(), m.meetingAt(), m.templateId(), m.participantIds().stream().map(mapped).toList(),
                m.speakers().stream().map(s -> new TransferMarker.Speaker(s.speakerId(), s.label(), mapped.apply(s.participantId()))).toList(), m.failure(), r.participant() == null ? null : digest, transfer);
        StructuredMinutes minutes = r.minutes();
        if (minutes != null) minutes = new StructuredMinutes(minutes.templateId(), minutes.templateVersion(), minutes.summary(), minutes.discussionPoints(), minutes.decisions(),
                minutes.actionItems().stream().map(a -> new StructuredMinutes.ActionItem(mapped.apply(a.ownerParticipantId()), a.task(), a.dueDate())).toList(), minutes.followUps());
        return new TransferRecord(r.sourceDocumentId(), r.sourceRevision(), r.documentUrl(), marker, r.participant() == null ? r.title() : r.participant().name(), minutes, r.transcript(),
                r.participants().stream().map(p -> new ParticipantSummary(mapped.apply(p.id()), p.name())).toList(), r.participant(),
                r.renderedMarkdown(), null);
    }
    private static TransferCodec.ValidationException invalid() { return new TransferCodec.ValidationException(TransferCodec.Code.INVALID_RECORD); }
    private static TransferAdapterException conflict() { return new TransferAdapterException(TransferAdapterException.Code.CONFLICT); }
}
