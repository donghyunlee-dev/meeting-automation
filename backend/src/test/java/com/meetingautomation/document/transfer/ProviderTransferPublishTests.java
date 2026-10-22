package com.meetingautomation.document.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderTransferPublishTests {
    @Test
    void meetingDataSidecarRoundTripKeepsInternalIdentityAndUserFacingDocumentLocation() {
        TransferRecord expected = meeting("ms_sidecar_roundtrip")
                .withRenderedMarkdown("# Planning\n\n## Decisions\n- Launch on Friday")
                .withStorage("https://documents.test/final-minutes", "ms_sidecar_roundtrip");
        TransferPageCodec pageCodec = new TransferPageCodec();

        TransferRecord actual = pageCodec.decode("metadata-page", "revision-1", "https://documents.test/metadata-page",
                "ms_sidecar_roundtrip", pageCodec.encode(expected));

        assertThat(actual.title()).isEqualTo("Planning");
        assertThat(actual.documentUrl()).isEqualTo("https://documents.test/final-minutes");
        assertThat(actual.storageKey()).isEqualTo("ms_sidecar_roundtrip");
        assertThat(actual.renderedMarkdown()).isEqualTo(expected.renderedMarkdown());
        assertThat(new TransferCodec().canonicalDigest(actual)).isEqualTo(new TransferCodec().canonicalDigest(expected));
    }

    @Test
    void publishMeetingUsesStandardPageCodecAndReusesUniqueExternalSessionId() {
        FakeTransferProvider provider = new FakeTransferProvider();
        provider.addParticipant();
        TransferRecord meeting = meeting("ms_publish_codec");

        MeetingDocumentRef first = provider.publishMeeting(meeting);
        MeetingDocumentRef replay = provider.publishMeeting(meeting);

        assertThat(first).isEqualTo(new MeetingDocumentRef("page-1", "https://documents.test/page-1"));
        assertThat(replay).isEqualTo(first);
        assertThat(provider.creates).isEqualTo(1);
        assertThat(provider.publishedIndexes).containsExactly("ms_publish_codec");
        TransferRecord readBack = provider.exportMeeting(first.documentId());
        assertThat(readBack.marker().externalSessionId()).isEqualTo("ms_publish_codec");
        assertThat(readBack.marker().speakers()).containsExactly(
                new TransferMarker.Speaker("speaker_1", "Speaker one", "pt_ada"));
        assertThat(readBack.participants()).containsExactly(new ParticipantSummary("pt_ada", "Ada"));
        assertThat(readBack.minutes()).isEqualTo(meeting.minutes());
        assertThat(readBack.transcript()).isEqualTo(meeting.transcript());
    }

    @Test
    void ambiguousCreateOutcomeIsReconciledByExternalSessionIdWithoutCreatingAgain() {
        FakeTransferProvider provider = new FakeTransferProvider();
        provider.addParticipant();
        provider.storeThenThrow = true;

        MeetingDocumentRef result = provider.publishMeeting(meeting("ms_publish_ambiguous"));

        assertThat(result.documentId()).isEqualTo("page-1");
        assertThat(provider.creates).isEqualTo(1);
    }

    @Test
    void existingDuplicateExternalSessionIdIsRejectedWithoutCreating() {
        FakeTransferProvider provider = new FakeTransferProvider();
        provider.addParticipant();
        provider.publishMeeting(meeting("ms_publish_duplicate"));
        provider.duplicateExistingSession = true;

        assertThatThrownBy(() -> provider.publishMeeting(meeting("ms_publish_duplicate")))
                .isInstanceOf(TransferCodec.ValidationException.class)
                .extracting(failure -> ((TransferCodec.ValidationException) failure).code())
                .isEqualTo(TransferCodec.Code.INVALID_RECORD);
        assertThat(provider.creates).isEqualTo(1);
    }

    @Test
    void manifestLookupFailureNeverFallsBackToCreate() {
        FakeTransferProvider provider = new FakeTransferProvider();
        provider.addParticipant();
        provider.failLookup = true;

        assertThatThrownBy(() -> provider.publishMeeting(meeting("ms_lookup_failure")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(provider.creates).isZero();
    }

    @Test
    void definitiveCreateFailureRequiresReconciliationAndNeverClaimsSuccess() {
        FakeTransferProvider provider = new FakeTransferProvider();
        provider.addParticipant();
        provider.failCreateBeforeStore = true;

        assertThatThrownBy(() -> provider.publishMeeting(meeting("ms_create_failure")))
                .isInstanceOf(TransferAdapterException.class)
                .extracting(failure -> ((TransferAdapterException) failure).code())
                .isEqualTo(TransferAdapterException.Code.RECONCILIATION_REQUIRED);
        assertThat(provider.creates).isEqualTo(1);
        assertThat(provider.records.values().stream().noneMatch(record -> record.participant() == null)).isTrue();
    }

    private static TransferRecord meeting(String sessionId) {
        OffsetDateTime meetingAt = OffsetDateTime.parse("2026-10-14T09:00:00+09:00");
        StructuredMinutes minutes = new StructuredMinutes("default.md", "1.0.0", "Summary",
                List.of("Discussion"), List.of("Decision"),
                List.of(new StructuredMinutes.ActionItem("pt_ada", "Follow up", null)), List.of("Next"));
        TransferMarker marker = new TransferMarker("meeting-automation", "meeting", "1.0", sessionId,
                meetingAt, "default.md", List.of("pt_ada"),
                List.of(new TransferMarker.Speaker("speaker_1", "Speaker one", "pt_ada")),
                null, null, null);
        return new TransferRecord(sessionId, "snapshot-1", null, marker, "Planning", minutes,
                List.of(new TranscriptSegment("seg_1", "speaker_1", 0, 1000, "Transcript line")),
                List.of(new ParticipantSummary("pt_ada", "Ada")), null);
    }

    private static final class FakeTransferProvider extends ProviderTransferSupport {
        private final Map<String, TransferRecord> records = new HashMap<>();
        private int creates;
        private final List<String> publishedIndexes = new ArrayList<>();
        private boolean storeThenThrow;
        private boolean duplicateExistingSession;
        private boolean failLookup;
        private boolean failCreateBeforeStore;

        private void addParticipant() {
            Participant participant = new Participant("pt_ada", "Ada", "ada@example.test");
            TransferMarker marker = ParticipantTransferMarkers.refreshed(participant, null);
            records.put("pt_ada", new TransferRecord("pt_ada", "revision-1",
                    "https://documents.test/pt_ada", marker, participant.name(), null, null, List.of(),
                    participant));
        }

        @Override protected String fingerprint() { return "sha256:" + "a".repeat(64); }
        @Override protected DocumentStructure structure() { return new DocumentStructure("root", "meetings", "participants"); }
        @Override protected List<String> children(String root) {
            if (failLookup && root.equals("meetings")) throw new IllegalStateException("provider lookup failed");
            if (root.equals("participants")) return records.values().stream()
                    .filter(record -> record.participant() != null).map(TransferRecord::sourceDocumentId).toList();
            List<String> ids = new ArrayList<>(records.values().stream().filter(record -> record.participant() == null)
                    .map(TransferRecord::sourceDocumentId).toList());
            if (duplicateExistingSession && !ids.isEmpty()) ids.add("duplicate");
            return ids;
        }
        @Override protected TransferRecord read(String id) {
            if (id.equals("duplicate")) {
                TransferRecord first = records.values().stream().filter(record -> record.participant() == null)
                        .findFirst().orElseThrow();
                return new TransferRecord("duplicate", first.sourceRevision(), first.documentUrl(), first.marker(),
                        first.title(), first.minutes(), first.transcript(), first.participants(), null);
            }
            return records.get(id);
        }
        @Override protected String create(String parent, TransferRecord record) {
            creates++;
            if (failCreateBeforeStore) throw new IllegalStateException("provider create failed");
            String id = "page-" + creates;
            TransferPageCodec codec = new TransferPageCodec();
            List<TransferPageCodec.Block> blocks = codec.encode(record);
            TransferRecord decoded = codec.decode(id, "revision-1", "https://documents.test/" + id,
                    record.title(), blocks);
            records.put(id, decoded);
            if (storeThenThrow) {
                storeThenThrow = false;
                throw new IllegalStateException("provider-response-secret");
            }
            return id;
        }
        @Override protected void afterMeetingPublished(TransferRecord meeting) {
            publishedIndexes.add(meeting.marker().externalSessionId());
        }
    }
}
