package com.meetingautomation.transcription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.document.TranscriptResult;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiarizationResultAdapterTests {
    private final DiarizationResultAdapter adapter = new DiarizationResultAdapter();

    @Test
    void assignsSpeakersByFirstUtteranceAndSortsSegmentsWithStableGeneratedIds() {
        var response = response(
                List.of("provider-1", "provider-2", "provider-unused"),
                List.of(
                        segment("duplicate", "provider-1", 400, 700, "later first speaker"),
                        segment("duplicate", "provider-2", 100, 500, "earliest speaker"),
                        segment(null, "provider-1", 100, 300, "same start, shorter segment"),
                        segment(null, "provider-1", 100, 300, "same time, input order")));

        TranscriptResult result = adapter.normalize(response, 1_000);

        assertThat(result.speakers())
                .extracting(speaker -> speaker.speakerId() + ":" + speaker.label())
                .containsExactly("speaker_a:Speaker A", "speaker_b:Speaker B");
        assertThat(result.segments())
                .extracting(segment -> segment.segmentId() + ":" + segment.speakerId() + ":"
                        + segment.startMs() + ":" + segment.endMs() + ":" + segment.text())
                .containsExactly(
                        "seg_001:speaker_b:100:300:same start, shorter segment",
                        "seg_002:speaker_b:100:300:same time, input order",
                        "seg_003:speaker_a:100:500:earliest speaker",
                        "seg_004:speaker_b:400:700:later first speaker");
    }

    @Test
    void preservesOverlappingTimesAndTextAndIgnoresProviderSegmentIds() {
        var response = response(
                List.of("left", "right"),
                List.of(
                        segment("provider-secret-1", "left", 1_000, 3_000, "  exact left text  "),
                        segment("provider-secret-2", "right", 2_000, 4_000, "right overlaps")));

        TranscriptResult result = adapter.normalize(response, 4_000);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).endMs()).isEqualTo(3_000);
        assertThat(result.segments().get(0).text()).isEqualTo("  exact left text  ");
        assertThat(result.segments().get(1).startMs()).isEqualTo(2_000);
        assertThat(result.segments().get(1).endMs()).isEqualTo(4_000);
        assertThat(result.segments().get(1).text()).isEqualTo("right overlaps");
        assertThat(result.segments()).extracting("segmentId").containsExactly("seg_001", "seg_002");
    }

    @Test
    void generatesAlphabeticSpeakerIdsBeyondTwentySixSpeakers() {
        List<String> labels = new ArrayList<>();
        List<ProviderTranscriptionResponse.Segment> segments = new ArrayList<>();
        for (int index = 0; index < 27; index++) {
            String label = "provider-" + index;
            labels.add(label);
            segments.add(segment(null, label, index, index + 1, "utterance " + index));
        }

        TranscriptResult result = adapter.normalize(response(labels, segments), 100);

        assertThat(result.speakers()).hasSize(27);
        assertThat(result.speakers().get(25).speakerId()).isEqualTo("speaker_z");
        assertThat(result.speakers().get(26).speakerId()).isEqualTo("speaker_aa");
        assertThat(result.speakers().get(26).label()).isEqualTo("Speaker AA");
    }

    @Test
    void returnsAnEmptyStandardResultForValidNoSpeechResponse() {
        TranscriptResult result = adapter.normalize(new ProviderTranscriptionResponse(List.of(), List.of()), 0);

        assertThat(result.speakers()).isEmpty();
        assertThat(result.segments()).isEmpty();

        TranscriptResult unusedDeclaration = adapter.normalize(
                new ProviderTranscriptionResponse(List.of(new ProviderTranscriptionResponse.Speaker("unused")),
                        List.of()), 0);
        assertThat(unusedDeclaration.speakers()).isEmpty();
        assertThat(unusedDeclaration.segments()).isEmpty();
    }

    @Test
    void rejectsUnknownOrBlankLabelsInvalidTimesAndBlankTranscriptBeforeNormalization() {
        var knownSpeaker = List.of(new ProviderTranscriptionResponse.Speaker("provider-label"));

        assertInvalidResponse(knownSpeaker,
                List.of(segment(null, "unknown-provider-label", 0, 1, "private phrase")));
        assertInvalidResponse(List.of(new ProviderTranscriptionResponse.Speaker("  ")), List.of());
        assertInvalidResponse(knownSpeaker,
                List.of(segment(null, "provider-label", -1, 1, "private phrase")));
        assertInvalidResponse(knownSpeaker,
                List.of(segment(null, "provider-label", 2, 1, "private phrase")));
        assertInvalidResponse(knownSpeaker,
                List.of(segment(null, "provider-label", 0, 1, " \t ")));
    }

    @Test
    void rejectsSegmentsOutsideMeetingDurationWithoutExposingTranscriptText() {
        var response = response(List.of("provider-label"),
                List.of(segment(null, "provider-label", 50, 101, "private transcript phrase")));

        assertThatThrownBy(() -> adapter.normalize(response, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private transcript phrase")
                .hasMessageNotContaining("provider-label");
    }

    @Test
    void rejectsInvalidDurationAndKeepsZeroLengthBoundarySegmentValid() {
        var boundary = response(List.of("speaker"), List.of(segment(null, "speaker", 100, 100, "boundary")));

        assertThat(adapter.normalize(boundary, 100).segments())
                .singleElement()
                .satisfies(segment -> {
                    assertThat(segment.startMs()).isEqualTo(100);
                    assertThat(segment.endMs()).isEqualTo(100);
                });
        assertThatThrownBy(() -> adapter.normalize(boundary, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("boundary");
    }

    @Test
    void replayIsDeterministicAndOutputContainsOnlyStandardFields() throws Exception {
        var response = response(
                List.of("provider-account-42"),
                List.of(segment("provider-segment-opaque", "provider-account-42", 10, 20, "secret transcript")));

        TranscriptResult first = adapter.normalize(response, 20);
        TranscriptResult second = adapter.normalize(response, 20);
        assertThat(second).isEqualTo(first);
        assertThat(first.speakers().getFirst().speakerId()).isEqualTo("speaker_a");
        assertThat(first.speakers().getFirst().label()).isEqualTo("Speaker A");
        assertThat(first.segments().getFirst().segmentId()).isEqualTo("seg_001");
        assertThat(first.segments().getFirst().text()).isEqualTo("secret transcript");
        assertThat(Arrays.stream(TranscriptResult.class.getRecordComponents())
                .map(component -> component.getName())).containsExactly("speakers", "segments");
        assertThat(Arrays.stream(first.speakers().getFirst().getClass().getRecordComponents())
                .map(component -> component.getName())).containsExactly("speakerId", "label");
        assertThat(Arrays.stream(first.segments().getFirst().getClass().getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("segmentId", "speakerId", "startMs", "endMs", "text");
        assertThat(first.toString()).doesNotContain(
                "provider-account-42", "provider-segment-opaque", "secret transcript");
        assertThat(first.segments().getFirst().toString()).doesNotContain("secret transcript");
    }

    @Test
    void outputListsAreImmutable() {
        TranscriptResult result = adapter.normalize(
                response(List.of("speaker"), List.of(segment(null, "speaker", 0, 1, "hello"))), 1);

        assertThatThrownBy(() -> result.speakers().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.segments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static ProviderTranscriptionResponse response(
            List<String> speakerLabels, List<ProviderTranscriptionResponse.Segment> segments) {
        return new ProviderTranscriptionResponse(
                speakerLabels.stream().map(ProviderTranscriptionResponse.Speaker::new).toList(), segments);
    }

    private static void assertInvalidResponse(
            List<ProviderTranscriptionResponse.Speaker> speakers,
            List<ProviderTranscriptionResponse.Segment> segments) {
        assertThatThrownBy(() -> new ProviderTranscriptionResponse(speakers, segments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("unknown-provider-label")
                .hasMessageNotContaining("private phrase");
    }

    private static ProviderTranscriptionResponse.Segment segment(
            String providerSegmentId, String providerLabel, long startMs, long endMs, String text) {
        return new ProviderTranscriptionResponse.Segment(providerSegmentId, providerLabel, startMs, endMs, text);
    }
}
