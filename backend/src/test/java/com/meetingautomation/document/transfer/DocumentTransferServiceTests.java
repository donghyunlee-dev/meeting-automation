package com.meetingautomation.document.transfer;

import com.meetingautomation.document.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentTransferServiceTests {
    private static final TransferCodec CODEC = new TransferCodec();
    private static final String SOURCE_FP = "sha256:" + "1".repeat(64);
    private static final String TARGET_FP = "sha256:" + "2".repeat(64);
    private static final String THIRD_FP = "sha256:" + "3".repeat(64);

    @Test void transferImportsParticipantsFirstAndRewritesEveryMeetingReference() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");

        TransferResult result = new DocumentTransferService().transfer(source, target);

        assertEquals(List.of("participant:source-person", "meeting:source-meeting"), target.writes);
        assertEquals("target-person", target.meetings.get("target-meeting").marker().participantIds().getFirst());
        assertEquals("target-person", target.meetings.get("target-meeting").marker().speakers().getFirst().participantId());
        assertEquals("target-person", target.meetings.get("target-meeting").minutes().actionItems().getFirst().ownerParticipantId());
        assertEquals("session-source-meeting", target.meetings.get("target-meeting").marker().externalSessionId());
        assertEquals(OffsetDateTime.parse("2026-10-10T10:00:00+09:00"), target.meetings.get("target-meeting").marker().meetingAt());
        assertEquals("meeting-template", target.meetings.get("target-meeting").marker().templateId());
        assertEquals("minutes-template", target.meetings.get("target-meeting").minutes().templateId());
        assertEquals("Decide \uD83D\uDE80", target.meetings.get("target-meeting").minutes().summary());
        assertEquals(new TranscriptSegment("s1", "sp1", 1, 8, "spoken"), target.meetings.get("target-meeting").transcript().getFirst());
        assertEquals("Synthetic review: UTF-8 재발견 🚀", target.meetings.get("target-meeting").title());
        assertEquals(1, result.participantsCopied());
        assertEquals(1, result.meetingsCopied());
        assertEquals(0, result.meetingsReused());
    }

    @Test void transferProgressReportsContentFreeCountsAndParticipantMapAtEachSafePoint() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");
        List<TransferProgress> progress = new ArrayList<>();
        List<String> verificationOrder = new ArrayList<>();
        source.orderEvents = verificationOrder;

        new DocumentTransferService().transfer(source, target, update -> {
            progress.add(update);
            verificationOrder.add("PROGRESS:" + update.stage());
        });

        assertEquals(List.of("PREFLIGHT", "PARTICIPANTS", "PARTICIPANTS_VERIFIED", "MEETINGS", "VERIFY"),
                progress.stream().map(TransferProgress::stage).toList());
        assertEquals(new TransferProgress("PARTICIPANTS", 1, 1, 0, 1, 0, 0,
                Map.of("source-person", "target-person")), progress.get(1));
        assertEquals(1, progress.getLast().participantsTotal());
        assertEquals(1, progress.getLast().participantsCopied());
        assertEquals(1, progress.getLast().meetingsTotal());
        assertEquals(1, progress.getLast().meetingsCopied());
        assertEquals(List.of("PROGRESS:VERIFY", "SOURCE_READ"),
                verificationOrder.subList(verificationOrder.size() - 2, verificationOrder.size()));
    }

    @Test void transferCancellationAtSafePointPreservesPartialTargetAndDoesNotStartNextMeetingWrite() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");

        var failure = assertThrows(TransferCancelledException.class, () -> new DocumentTransferService().transfer(source,
                target, progress -> {
                    if ("PARTICIPANTS".equals(progress.stage())) throw new TransferCancelledException();
                }));

        assertNotNull(failure);
        assertEquals(1, target.participants.size());
        assertTrue(target.meetings.isEmpty());
        assertEquals(List.of("participant:source-person"), target.writes);
    }

    @Test void transferPreservesAbsentOptionalSectionsAndFailureMetadata() {
        var source = new StatefulPort(SOURCE_FP, "source");
        var failed = meeting("failed", List.of(), null, null,
                new TransferMarker.Failure("transcription", "NO_SPEECH", "retained"));
        source.addMeeting(failed);
        var target = new StatefulPort(TARGET_FP, "target");

        new DocumentTransferService().transfer(source, target);

        TransferRecord copied = target.meetings.get("target-meeting");
        assertNull(copied.minutes());
        assertNull(copied.transcript());
        assertEquals(failed.marker().failure(), copied.marker().failure());
        assertEquals(failed.marker().meetingAt(), copied.marker().meetingAt());
        assertEquals(failed.marker().templateId(), copied.marker().templateId());
    }

    @Test void transferRejectsDuplicateSourceTransferKeysBeforeAnyTargetWrite() {
        var source = new StatefulPort(SOURCE_FP, "source");
        source.addParticipant(participant("p1", "Ada", "ada@example.test"));
        source.addParticipant(participant("p2", "Grace", "grace@example.test"));
        source.duplicateParticipantTransferKey = true;
        var target = new StatefulPort(TARGET_FP, "target");

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.DUPLICATE_TRANSFER_KEY, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferRejectsUnmarkedExternalSessionCollisionBeforeAnyTargetWrite() {
        var source = new StatefulPort(SOURCE_FP, "source");
        source.addParticipant(participant("source-person", "Ada", "ada@example.test"));
        source.addMeeting(meeting("source-meeting", List.of("source-person"), null, null, null));
        var target = new StatefulPort(TARGET_FP, "target");
        target.addMeeting(meeting("legacy-meeting", List.of(), null, null, null));
        target.meetings.put("legacy-meeting", withExternalSession(target.meetings.get("legacy-meeting"), "session-source-meeting"));

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.UNMARKED_COLLISION, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferDoesNotReuseUnmarkedParticipantEvenWhenProviderIdAndFingerprintMatch() {
        var source = new StatefulPort(SOURCE_FP, "source");
        source.addParticipant(participant("same-id", "Ada", "ada@example.test"));
        var target = new StatefulPort(SOURCE_FP, "target");
        target.addParticipant(participant("same-id", "Ada", "ada@example.test"));

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.UNMARKED_COLLISION, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferRejectsExistingTransferKeyWithDifferentDigestBeforeAnyTargetWrite() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");
        TransferRecord expected = source.participants.get("source-person");
        target.addParticipant(importedParticipant(expected, SOURCE_FP, "target-person", TARGET_FP));
        target.participants.put("target-person", withParticipantName(target.participants.get("target-person"), "Other Ada"));

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.DIGEST_MISMATCH, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferReusesOnlyExactTransferKeyAndCanonicalDigestMatches() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");
        var sourceParticipant = source.participants.get("source-person");
        target.addParticipant(importedParticipant(sourceParticipant, SOURCE_FP, "target-person", TARGET_FP));
        target.addMeeting(target.remap(source.meetings.get("source-meeting"), Map.of("source-person", "target-person"),
                SOURCE_FP, "target-meeting"));

        TransferResult result = new DocumentTransferService().transfer(source, target);

        assertEquals(1, result.participantsReused());
        assertEquals(1, result.meetingsReused());
        assertEquals(0, result.participantsCopied());
        assertEquals(0, result.meetingsCopied());
        assertTrue(target.writes.isEmpty());
        assertEquals(Map.of("source-person", "target-person"), result.participantIdMap());
    }

    @Test void transferComposesParticipantMappingsAcrossMultipleProviderHopsAndReusesTheCopy() {
        var original = sourceWithOneParticipantAndMeeting();
        var intermediate = new StatefulPort(TARGET_FP, "intermediate");
        intermediate.addParticipant(importedParticipant(original.participants.get("source-person"), SOURCE_FP,
                "intermediate-person", TARGET_FP));
        intermediate.addMeeting(intermediate.remap(original.meetings.get("source-meeting"),
                Map.of("source-person", "intermediate-person"), SOURCE_FP, "intermediate-meeting"));
        var target = new StatefulPort(THIRD_FP, "target");

        TransferResult copied = new DocumentTransferService().transfer(intermediate, target);
        assertEquals(CODEC.transferKey(TARGET_FP, "intermediate-person"),
                target.participants.get("target-person").marker().transfer().transferKey());
        assertEquals(Map.of("source-person", "intermediate-person"),
                intermediate.meetings.get("intermediate-meeting").marker().transfer().participantIdMap());
        TransferResult replay = new DocumentTransferService().transfer(intermediate, target);

        assertEquals(1, copied.meetingsCopied());
        assertEquals(1, replay.meetingsReused());
        assertEquals(Map.of("source-person", "target-person"),
                target.meetings.get("target-meeting").marker().transfer().participantIdMap());
        assertEquals(CODEC.canonicalDigest(original.meetings.get("source-meeting")),
                CODEC.canonicalDigest(target.meetings.get("target-meeting")));
    }

    @Test void transferRejectsReusableMeetingWithStaleParticipantMapBeforeWritingParticipants() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");
        target.addParticipant(importedParticipant(source.participants.get("source-person"), SOURCE_FP, "target-person", TARGET_FP));
        target.addParticipant(importedParticipant(participant("decoy-source-person", "Grace", "grace@example.test"),
                CODEC.connectionFingerprint("notion", "decoy-root"), "wrong-target-person", TARGET_FP));
        target.addMeeting(target.remap(source.meetings.get("source-meeting"), Map.of("source-person", "wrong-target-person"),
                SOURCE_FP, "reused-meeting"));

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertTrue(target.writes.isEmpty());
        assertEquals(TransferConflictException.Code.PARTICIPANT_MAPPING_MISMATCH, failure.code());
        assertEquals(0, target.createParticipantCalls);
        assertEquals(0, target.createMeetingCalls);
    }

    @Test void transferRejectsDuplicateTargetTransferMarkerBeforeAnyWrite() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");
        var imported = importedParticipant(source.participants.get("source-person"), SOURCE_FP, "target-person", TARGET_FP);
        target.addParticipant(imported);
        target.addParticipant(new TransferRecord("other-target-person", "t2", null, imported.marker(), "Ada", null, null,
                List.of(), new Participant("other-target-person", "Ada", "ada@example.test")));

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.DUPLICATE_TRANSFER_KEY, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferRejectsDifferentMarkedRecordSharingExternalSessionBeforeAnyWrite() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");
        target.addParticipant(importedParticipant(source.participants.get("source-person"), SOURCE_FP, "target-person", TARGET_FP));
        target.addMeeting(target.remap(source.meetings.get("source-meeting"), Map.of("source-person", "target-person"),
                CODEC.connectionFingerprint("confluence", "another-origin"), "other-meeting"));

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.TRANSFER_KEY_MISMATCH, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferRejectsReadbackMismatchWithTypedReconciliationOutcome() {
        var source = sourceWithOneParticipantAndMeeting();
        var target = new StatefulPort(TARGET_FP, "target");
        target.corruptMeetingReadback = true;

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.RECONCILIATION_REQUIRED, failure.code());
        assertEquals(1, target.createMeetingCalls);
    }

    @Test void transferDoesNotRetryAmbiguousCreateAndReportsReconciliationRequired() {
        var source = new StatefulPort(SOURCE_FP, "source");
        source.addParticipant(participant("source-person", "Ada", "ada@example.test"));
        var target = new StatefulPort(TARGET_FP, "target");
        target.ambiguousParticipantCreate = true;

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.RECONCILIATION_REQUIRED, failure.code());
        assertEquals(1, target.createParticipantCalls);
        assertEquals(1, target.participants.size());
    }

    @Test void transferRejectsBrokenReferencesBeforeAnyTargetWrite() {
        var source = new StatefulPort(SOURCE_FP, "source");
        source.addMeeting(meeting("source-meeting", List.of("missing-person"), null, null, null));
        var target = new StatefulPort(TARGET_FP, "target");

        var failure = assertThrows(TransferCodec.ValidationException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferCodec.Code.BROKEN_REFERENCE, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferValidatesEverySourceMeetingBeforeStartingAnyTargetWrite() {
        var source = sourceWithOneParticipantAndMeeting();
        source.addMeeting(meeting("later-broken", List.of("missing-person"), null, null, null));
        var target = new StatefulPort(TARGET_FP, "target");

        var failure = assertThrows(TransferCodec.ValidationException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferCodec.Code.BROKEN_REFERENCE, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferFinishesTargetManifestPreflightBeforeStartingAnyCreate() {
        var source = sourceWithOneParticipantAndMeeting();
        source.addMeeting(meeting("second-source-meeting", List.of("source-person"), null, null, null));
        var target = new StatefulPort(TARGET_FP, "target");
        target.addMeeting(meeting("legacy-later-meeting", List.of(), null, null, null));
        target.meetings.put("legacy-later-meeting", withExternalSession(target.meetings.get("legacy-later-meeting"), "session-second-source-meeting"));

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.UNMARKED_COLLISION, failure.code());
        assertTrue(target.writes.isEmpty());
    }

    @Test void transferRequiresCompleteOneToOneParticipantIdMapBeforeImportingMeetings() {
        var source = new StatefulPort(SOURCE_FP, "source");
        source.addParticipant(participant("person-one", "Ada", "ada@example.test"));
        source.addParticipant(participant("person-two", "Grace", "grace@example.test"));
        source.addMeeting(meeting("source-meeting", List.of("person-one", "person-two"), null, null, null));
        var target = new StatefulPort(TARGET_FP, "target");

        var failure = assertThrows(TransferCodec.ValidationException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferCodec.Code.BROKEN_REFERENCE, failure.code());
        assertEquals(List.of("participant:person-one", "participant:person-two"), target.writes);
        assertEquals(0, target.createMeetingCalls);
    }

    @Test void transferRejectsSourceRevisionChangeAfterCopyWithoutMutatingSource() {
        var source = sourceWithOneParticipantAndMeeting();
        source.changeMeetingOnFinalManifestRead = true;
        var target = new StatefulPort(TARGET_FP, "target");

        var failure = assertThrows(TransferConflictException.class, () -> new DocumentTransferService().transfer(source, target));

        assertEquals(TransferConflictException.Code.SOURCE_CHANGED, failure.code());
        assertTrue(source.sourceWrites.isEmpty());
        assertEquals(2, target.writes.size());
    }

    private static StatefulPort sourceWithOneParticipantAndMeeting() {
        var source = new StatefulPort(SOURCE_FP, "source");
        source.addParticipant(participant("source-person", "Ada", "ada@example.test"));
        var marker = meeting("source-meeting", List.of("source-person"),
                new StructuredMinutes("minutes-template", "2.4", "Decide 🚀", List.of("point"), List.of("decision"),
                        List.of(new StructuredMinutes.ActionItem("source-person", "deliver", LocalDate.parse("2026-10-20"))), List.of("follow")),
                List.of(new TranscriptSegment("s1", "sp1", 1, 8, "spoken")), null).marker();
        source.addMeeting(new TransferRecord("source-meeting", "r1", null, marker, "Synthetic review: UTF-8 재발견 🚀",
                new StructuredMinutes("minutes-template", "2.4", "Decide 🚀", List.of("point"), List.of("decision"),
                        List.of(new StructuredMinutes.ActionItem("source-person", "deliver", LocalDate.parse("2026-10-20"))), List.of("follow")),
                List.of(new TranscriptSegment("s1", "sp1", 1, 8, "spoken")),
                List.of(new ParticipantSummary("source-person", "Ada")), null));
        return source;
    }

    private static TransferRecord participant(String id, String name, String email) {
        var marker = new TransferMarker("meeting-automation", "participant", "1.0", null, null, null,
                List.of(), List.of(), null, "sha256:" + "0".repeat(64), null);
        var r = new TransferRecord(id, "r1", null, marker, name, null, null, List.of(), new Participant(id, name, email));
        marker = new TransferMarker(marker.format(), marker.recordType(), marker.schemaVersion(), null, null, null,
                List.of(), List.of(), null, CODEC.canonicalDigest(r), null);
        return new TransferRecord(id, "r1", null, marker, name, null, null, List.of(), r.participant());
    }

    private static TransferRecord meeting(String id, List<String> participantIds, StructuredMinutes minutes,
            List<TranscriptSegment> transcript, TransferMarker.Failure failure) {
        var marker = new TransferMarker("meeting-automation", "meeting", "1.0", "session-" + id,
                OffsetDateTime.parse("2026-10-10T10:00:00+09:00"), "meeting-template", participantIds,
                participantIds.isEmpty() ? List.of() : List.of(new TransferMarker.Speaker("sp1", "Speaker", participantIds.getFirst())),
                failure, null, null);
        return new TransferRecord(id, "r1", null, marker, "Synthetic " + id, minutes, transcript,
                participantIds.stream().map(pid -> new ParticipantSummary(pid, "Ada")).toList(), null);
    }

    private static TransferRecord withExternalSession(TransferRecord r, String session) {
        var m = r.marker();
        return new TransferRecord(r.sourceDocumentId(), r.sourceRevision(), r.documentUrl(), new TransferMarker(m.format(), m.recordType(), m.schemaVersion(), session,
                m.meetingAt(), m.templateId(), m.participantIds(), m.speakers(), m.failure(), null, null), r.title(), r.minutes(), r.transcript(), r.participants(), null);
    }

    private static TransferRecord importedParticipant(TransferRecord source, String sourceFp, String id, String targetFp) {
        String digest = CODEC.canonicalDigest(source);
        var t = new TransferMarker.Transfer(CODEC.transferKey(sourceFp, source.sourceDocumentId()), sourceFp,
                source.sourceDocumentId(), source.sourceRevision(), digest, Map.of());
        var r = source.participant();
        var m = new TransferMarker("meeting-automation", "participant", "1.0", null, null, null,
                List.of(), List.of(), null, digest, t);
        return new TransferRecord(id, "t1", null, m, r.name(), null, null, List.of(), new Participant(id, r.name(), r.email()));
    }

    private static TransferRecord withParticipantName(TransferRecord r, String name) {
        var marker = r.marker();
        var pendingMarker = new TransferMarker(marker.format(), marker.recordType(), marker.schemaVersion(), null, null, null,
                List.of(), List.of(), null, marker.canonicalDigest(), marker.transfer());
        var pending = new TransferRecord(r.sourceDocumentId(), r.sourceRevision(), r.documentUrl(), pendingMarker, name,
                null, null, List.of(), new Participant(r.participant().id(), name, r.participant().email()));
        var refreshedMarker = new TransferMarker(marker.format(), marker.recordType(), marker.schemaVersion(), null, null, null,
                List.of(), List.of(), null, CODEC.canonicalDigest(pending), marker.transfer());
        return new TransferRecord(r.sourceDocumentId(), r.sourceRevision(), r.documentUrl(), refreshedMarker, name,
                null, null, List.of(), pending.participant());
    }

    private static final class StatefulPort implements DocumentTransferPort {
        final String fingerprint;
        final String prefix;
        final Map<String, TransferRecord> participants = new LinkedHashMap<>();
        final Map<String, TransferRecord> meetings = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        final List<String> sourceWrites = new ArrayList<>();
        final Map<String, TransferRecord> sourceMeetings;
        final Map<String, TransferRecord> sourceParticipants;
        List<String> orderEvents = new ArrayList<>();
        int manifestReads;
        int createParticipantCalls;
        int createMeetingCalls;
        boolean duplicateParticipantTransferKey;
        boolean corruptMeetingReadback;
        boolean ambiguousParticipantCreate;
        boolean changeMeetingOnFinalManifestRead;

        StatefulPort(String fingerprint, String prefix) {
            this.fingerprint = fingerprint; this.prefix = prefix;
            sourceMeetings = prefix.equals("source") ? meetings : Map.of();
            sourceParticipants = prefix.equals("source") ? participants : Map.of();
        }
        void addParticipant(TransferRecord p) { participants.put(p.participant().id(), p); }
        void addMeeting(TransferRecord m) { meetings.put(m.sourceDocumentId(), m); }

        @Override public TransferManifest readManifest() {
            manifestReads++;
            if (prefix.equals("source")) orderEvents.add("SOURCE_READ");
            if (changeMeetingOnFinalManifestRead && manifestReads > 1 && !meetings.isEmpty()) {
                var existing = meetings.values().iterator().next();
                meetings.put(existing.sourceDocumentId(), new TransferRecord(existing.sourceDocumentId(), "r2", null,
                        existing.marker(), existing.title(), existing.minutes(), existing.transcript(), existing.participants(), null));
            }
            List<TransferRecord> participantList = new ArrayList<>(participants.values());
            if (duplicateParticipantTransferKey && participantList.size() > 1) {
                var first = participantList.getFirst(); var second = participantList.get(1);
                var fm = first.marker(); var sm = second.marker();
                var transfer = new TransferMarker.Transfer(CODEC.transferKey(SOURCE_FP, first.sourceDocumentId()), SOURCE_FP,
                        first.sourceDocumentId(), first.sourceRevision(), CODEC.canonicalDigest(first), Map.of());
                participantList.set(1, new TransferRecord(second.sourceDocumentId(), second.sourceRevision(), null,
                        new TransferMarker(sm.format(), sm.recordType(), sm.schemaVersion(), null, null, null, List.of(), List.of(), null,
                                CODEC.canonicalDigest(second), transfer), second.participant().name(), null, null, List.of(), second.participant()));
            }
            List<TransferManifest.Entry> entries = meetings.values().stream().map(m -> new TransferManifest.Entry(m.sourceDocumentId(), m.sourceRevision(),
                    CODEC.canonicalDigest(m), transferKey(m), m.marker().externalSessionId(), m.marker().participantIds())).toList();
            return new TransferManifest(fingerprint, participantList, entries);
        }
        private String transferKey(TransferRecord r) {
            return r.marker().transfer() == null ? CODEC.transferKey(fingerprint, r.sourceDocumentId()) : r.marker().transfer().transferKey();
        }
        @Override public TransferRecord exportMeeting(String id) { return meetings.get(id); }
        @Override public Participant importParticipant(TransferRecord record, String sourceFingerprint) {
            createParticipantCalls++; writes.add("participant:" + record.sourceDocumentId());
            var imported = importedParticipant(record, sourceFingerprint, prefix + "-person", fingerprint);
            addParticipant(imported);
            if (ambiguousParticipantCreate) throw new TransferAdapterException(TransferAdapterException.Code.RECONCILIATION_REQUIRED);
            return imported.participant();
        }
        @Override public MeetingDocumentRef importMeeting(TransferRecord record, Map<String, String> idMap, String sourceFingerprint) {
            createMeetingCalls++; writes.add("meeting:" + record.sourceDocumentId());
            String targetId = prefix + "-meeting";
            TransferRecord copied = remap(record, idMap, sourceFingerprint, targetId);
            if (corruptMeetingReadback) copied = new TransferRecord(copied.sourceDocumentId(), copied.sourceRevision(), null,
                    copied.marker(), "Corrupt readback", copied.minutes(), copied.transcript(), copied.participants(), null);
            addMeeting(copied);
            return new MeetingDocumentRef(targetId, null);
        }
        TransferRecord remap(TransferRecord r, Map<String,String> map, String sourceFingerprint, String targetId) {
            var mapper = (java.util.function.Function<String,String>) id -> id == null ? null : map.get(id);
            String digest = CODEC.canonicalDigest(r);
            Map<String, String> canonicalMap = new LinkedHashMap<>();
            if (r.marker().transfer() != null && !r.marker().transfer().participantIdMap().isEmpty()) {
                r.marker().transfer().participantIdMap().forEach((canonicalId, sourceId) ->
                        canonicalMap.put(canonicalId, map.get(sourceId)));
            } else canonicalMap.putAll(map);
            var transfer = new TransferMarker.Transfer(CODEC.transferKey(sourceFingerprint, r.sourceDocumentId()), sourceFingerprint,
                    r.sourceDocumentId(), r.sourceRevision(), digest, canonicalMap);
            var m = r.marker();
            var marker = new TransferMarker(m.format(), m.recordType(), m.schemaVersion(), m.externalSessionId(), m.meetingAt(), m.templateId(),
                    m.participantIds().stream().map(mapper).toList(), m.speakers().stream().map(s -> new TransferMarker.Speaker(s.speakerId(), s.label(), mapper.apply(s.participantId()))).toList(),
                    m.failure(), null, transfer);
            StructuredMinutes minutes = r.minutes() == null ? null : new StructuredMinutes(r.minutes().templateId(), r.minutes().templateVersion(), r.minutes().summary(),
                    r.minutes().discussionPoints(), r.minutes().decisions(), r.minutes().actionItems().stream().map(a ->
                    new StructuredMinutes.ActionItem(mapper.apply(a.ownerParticipantId()), a.task(), a.dueDate())).toList(), r.minutes().followUps());
            return new TransferRecord(targetId, "t1", null, marker, r.title(), minutes, r.transcript(),
                    r.participants().stream().map(p -> new ParticipantSummary(mapper.apply(p.id()), p.name())).toList(), null);
        }
    }
}
