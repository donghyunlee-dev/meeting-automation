package com.meetingautomation.document.transfer;

import static org.junit.jupiter.api.Assertions.*;

import com.meetingautomation.document.*;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class TransferCodecTests {
    private final TransferCodec codec = new TransferCodec();
    private static final String MARKER = """
        {"format":"meeting-automation","recordType":"meeting","schemaVersion":"1.0",
        "externalSessionId":"session-synthetic","meetingAt":"2026-10-10T10:00:00+09:00",
        "templateId":"default.md","participantIds":["source-pt"],
        "speakers":[{"speakerId":"speaker-a","label":"Synthetic A","participantId":"source-pt"}],
        "failure":null}
        """;

    @Test void markerRoundTripPreservesMetadataAndOffset() {
        TransferMarker marker = codec.parseMarker(List.of(MARKER));
        assertEquals("session-synthetic", marker.externalSessionId());
        assertEquals("+09:00", marker.meetingAt().getOffset().toString());
        assertEquals(List.of("source-pt"), marker.participantIds());
        assertEquals("speaker-a", marker.speakers().getFirst().speakerId());
        assertNull(marker.transfer());
        assertEquals(marker, codec.parseMarker(List.of(codec.encodeMarker(marker))));
        assertFalse(codec.encodeMarker(marker).contains("\"transfer\""));
    }
    @Test void parseMarkerJoinsRichTextFragmentsInOrder() {
        assertEquals("session-synthetic", codec.parseMarker(List.of(
                MARKER.substring(0, 43), MARKER.substring(43, 99), MARKER.substring(99))).externalSessionId());
    }
    @Test void missingMarkerHasTypedFailure() { failure(List.of(), TransferCodec.Code.MISSING_MARKER); }
    @Test void duplicateMarkersHaveTypedFailure() { failure(List.of(MARKER, MARKER), TransferCodec.Code.DUPLICATE_MARKER); }
    @Test void malformedMarkerHasTypedFailure() { failure(List.of(MARKER.substring(0, 80)), TransferCodec.Code.MALFORMED_MARKER); }
    @Test void unsupportedSchemaHasTypedFailure() { failure(List.of(MARKER.replace("1.0", "2.0")), TransferCodec.Code.UNSUPPORTED_SCHEMA); }
    @Test void missingRequiredMetadataHasTypedFailure() { failure(List.of(MARKER.replace("\"templateId\":\"default.md\",", "")), TransferCodec.Code.MALFORMED_MARKER); }
    @Test void duplicateJsonKeysCannotHideMalformedMetadata() {
        failure(List.of(MARKER.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\",\"schemaVersion\":\"1.0\"")), TransferCodec.Code.MALFORMED_MARKER);
    }
    @Test void canonicalDigest_isStableAcrossObjectKeyOrder() {
        String reordered = "{\"templateId\":\"default.md\",\"schemaVersion\":\"1.0\",\"recordType\":\"meeting\",\"format\":\"meeting-automation\",\"externalSessionId\":\"session-synthetic\",\"meetingAt\":\"2026-10-10T10:00:00+09:00\",\"speakers\":[{\"participantId\":\"source-pt\",\"label\":\"Synthetic A\",\"speakerId\":\"speaker-a\"}],\"participantIds\":[\"source-pt\"],\"failure\":null}";
        assertEquals(codec.canonicalDigest(meeting(codec.parseMarker(List.of(MARKER)), null)),
                codec.canonicalDigest(meeting(codec.parseMarker(List.of(reordered)), null)));
    }
    @Test void arrayOrderChangesDigest() {
        var first = new TranscriptSegment("seg-1", "speaker-a", 0, 10, "Synthetic first");
        var second = new TranscriptSegment("seg-2", "speaker-a", 10, 20, "Synthetic second");
        var marker = codec.parseMarker(List.of(MARKER));
        assertNotEquals(codec.canonicalDigest(meeting(marker, List.of(first, second))),
                codec.canonicalDigest(meeting(marker, List.of(second, first))));
    }
    @Test void providerMetadataAndTransferEnvelopeAreExcludedAndCopiedReferencesRestoreSourceDigest() {
        var source = meeting(codec.parseMarker(List.of(MARKER)), null);
        String digest = codec.canonicalDigest(source);
        var envelope = new TransferMarker.Transfer("sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                "source-doc", "revision-old", digest, Map.of("source-pt", "target-pt"));
        var marker = new TransferMarker("meeting-automation", "meeting", "1.0", "session-synthetic",
                source.marker().meetingAt(), "default.md", List.of("target-pt"),
                List.of(new TransferMarker.Speaker("speaker-a", "Synthetic A", "target-pt")), null, null, envelope);
        var sourceMinutes = new StructuredMinutes("default.md", "1", "Synthetic summary", List.of(), List.of(),
                List.of(new StructuredMinutes.ActionItem("source-pt", "Synthetic task", LocalDate.parse("2026-10-11"))), List.of());
        var targetMinutes = new StructuredMinutes("default.md", "1", "Synthetic summary", List.of(), List.of(),
                List.of(new StructuredMinutes.ActionItem("target-pt", "Synthetic task", LocalDate.parse("2026-10-11"))), List.of());
        var original = new TransferRecord("source-doc", "v1", "https://example.test/source", source.marker(), "Synthetic meeting",
                sourceMinutes, null, List.of(new ParticipantSummary("source-pt", "Synthetic A")), null);
        var copied = new TransferRecord("target-doc", "v2", "https://example.test/target", marker, "Synthetic meeting",
                targetMinutes, null, List.of(new ParticipantSummary("target-pt", "Synthetic A")), null);
        assertEquals(codec.canonicalDigest(original), codec.canonicalDigest(copied));
        assertEquals(marker, codec.parseMarker(List.of(codec.encodeMarker(marker))));
    }
    @Test void absentContentAndFailureMetadataRemainAbsentAndPreserved() {
        var marker = codec.parseMarker(List.of(MARKER.replace("\"failure\":null",
                "\"failure\":{\"stage\":\"transcription\",\"errorCode\":\"SYNTHETIC_ERROR\",\"audioDisposition\":\"deleted\"}")));
        var record = meeting(marker, null);
        assertNull(record.minutes());
        assertNull(record.transcript());
        assertEquals("SYNTHETIC_ERROR", codec.parseMarker(List.of(codec.encodeMarker(marker))).failure().errorCode());
        assertNotEquals(codec.canonicalDigest(record), codec.canonicalDigest(meeting(codec.parseMarker(List.of(MARKER)), null)));
    }
    @Test void canonicalDigestRejectsTargetParticipantReferenceMissingFromReverseMap() {
        var copied = copiedMeeting(Map.of("source-pt", "target-pt"), "unmapped-target-pt");

        assertEquals(TransferCodec.Code.BROKEN_REFERENCE,
                assertThrows(TransferCodec.ValidationException.class, () -> codec.canonicalDigest(copied)).code());
    }
    @Test void canonicalDigestRejectsTwoSourceParticipantsMappedToOneTargetParticipant() {
        var copied = copiedMeeting(Map.of("source-pt", "target-pt", "other-source-pt", "target-pt"), "target-pt");

        assertEquals(TransferCodec.Code.BROKEN_REFERENCE,
                assertThrows(TransferCodec.ValidationException.class, () -> codec.canonicalDigest(copied)).code());
    }
    @Test void participantDigestIgnoresLocalIdAndMarkerStoresContentDigestOnly() {
        var marker = new TransferMarker("meeting-automation", "participant", "1.0", null, null, null,
                List.of(), List.of(), null, "sha256:" + "a".repeat(64), null);
        var first = new TransferRecord("pt-source", "v1", null, marker, null, null, null, List.of(),
                new Participant("pt-source", "Synthetic A", "synthetic@example.test"));
        var second = new TransferRecord("pt-target", "v2", null, marker, null, null, null, List.of(),
                new Participant("pt-target", "Synthetic A", "synthetic@example.test"));
        assertEquals(codec.canonicalDigest(first), codec.canonicalDigest(second));
        assertFalse(codec.encodeMarker(marker).contains("synthetic@example.test"));
        assertEquals(marker, codec.parseMarker(List.of(codec.encodeMarker(marker))));
    }
    @Test void transferKeyUsesExactCanonicalJsonAndLowercaseSha256() {
        assertEquals("sha256:5b37f1768abcb152b51611dff0b8a4df8ddbf52e6fb3edfab0b93fd43abb24e2", codec.transferKey("sha256:" + "b".repeat(64), "source-doc"));
    }
    @Test void connectionFingerprintUsesProviderAndRootOnly() {
        var fingerprint = codec.connectionFingerprint("notion", "root-synthetic");
        assertTrue(fingerprint.matches("sha256:[0-9a-f]{64}"));
        assertEquals(fingerprint, codec.connectionFingerprint("notion", "root-synthetic"));
        assertNotEquals(fingerprint, codec.connectionFingerprint("confluence", "root-synthetic"));
        assertNotEquals(fingerprint, codec.connectionFingerprint("notion", "other-root"));
    }
    @Test void transferKeyRejectsRawCredentialsAndEmailInPlaceOfFingerprint() {
        for (String raw : List.of("Bearer synthetic-token", "synthetic@example.test", "")) {
            assertEquals(TransferCodec.Code.INVALID_IDENTITY, assertThrows(TransferCodec.ValidationException.class,
                    () -> codec.transferKey(raw, "source-doc")).code());
        }
    }
    @Test void connectionFingerprintRejectsAccountEmailAsRootIdentity() {
        assertEquals(TransferCodec.Code.INVALID_IDENTITY,
                assertThrows(TransferCodec.ValidationException.class,
                        () -> codec.connectionFingerprint("notion", "synthetic@example.test")).code());
    }
    @Test void connectionFingerprintRejectsBearerCredentialAsRootIdentity() {
        assertEquals(TransferCodec.Code.INVALID_IDENTITY,
                assertThrows(TransferCodec.ValidationException.class,
                        () -> codec.connectionFingerprint("notion", "Bearer synthetic-token")).code());
    }
    @Test void connectionFingerprintRejectsBlankRootIdentity() {
        assertEquals(TransferCodec.Code.INVALID_IDENTITY,
                assertThrows(TransferCodec.ValidationException.class,
                        () -> codec.connectionFingerprint("notion", " ")).code());
    }
    @Test void connectionFingerprintRejectsUnsupportedProvider() {
        assertEquals(TransferCodec.Code.INVALID_IDENTITY,
                assertThrows(TransferCodec.ValidationException.class,
                        () -> codec.connectionFingerprint("unsupported-provider", "root-synthetic")).code());
    }
    private void failure(List<String> blocks, TransferCodec.Code expected) {
        assertEquals(expected, assertThrows(TransferCodec.ValidationException.class, () -> codec.parseMarker(blocks)).code());
    }
    private TransferRecord meeting(TransferMarker marker, List<TranscriptSegment> transcript) {
        return new TransferRecord("source-doc", "revision-1", "https://example.test/source", marker,
                "Synthetic meeting", null, transcript, List.of(new ParticipantSummary("source-pt", "Synthetic A")), null);
    }
    private TransferRecord copiedMeeting(Map<String, String> participantIdMap, String targetParticipantId) {
        var envelope = new TransferMarker.Transfer("sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                "source-doc", "revision-1", "sha256:" + "c".repeat(64), participantIdMap);
        var marker = new TransferMarker("meeting-automation", "meeting", "1.0", "session-synthetic",
                OffsetDateTime.parse("2026-10-10T10:00:00+09:00"), "default.md", List.of(targetParticipantId),
                List.of(new TransferMarker.Speaker("speaker-a", "Synthetic A", targetParticipantId)), null, null, envelope);
        return new TransferRecord("target-doc", "revision-2", "https://example.test/target", marker,
                "Synthetic meeting", null, null, List.of(new ParticipantSummary(targetParticipantId, "Synthetic A")), null);
    }
}
