package com.meetingautomation.transcription;

import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.TranscriptResult;
import com.meetingautomation.document.TranscriptSegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Converts provider-neutral diarization labels to the application's stable transcript contract. */
@Component
public final class DiarizationResultAdapter {
    private static final String INVALID_RESULT = "Transcription result is invalid.";

    public TranscriptResult normalize(ProviderTranscriptionResponse response, long meetingDurationMs) {
        if (response == null || meetingDurationMs < 0) {
            throw invalid();
        }

        List<IndexedSegment> orderedSegments = new ArrayList<>(response.segments().size());
        Map<String, FirstUtterance> firstUtteranceByLabel = new HashMap<>();
        Map<String, Boolean> declaredLabels = new HashMap<>();
        for (ProviderTranscriptionResponse.Speaker speaker : response.speakers()) {
            if (speaker == null || isBlank(speaker.providerLabel())
                    || declaredLabels.put(speaker.providerLabel(), Boolean.TRUE) != null) {
                throw invalid();
            }
        }

        int inputOrdinal = 0;
        for (ProviderTranscriptionResponse.Segment segment : response.segments()) {
            if (segment == null || isBlank(segment.providerLabel())
                    || !declaredLabels.containsKey(segment.providerLabel())
                    || segment.startMs() < 0 || segment.endMs() < segment.startMs()
                    || segment.endMs() > meetingDurationMs || isBlank(segment.text())) {
                throw invalid();
            }

            var indexed = new IndexedSegment(segment, inputOrdinal);
            orderedSegments.add(indexed);
            firstUtteranceByLabel.merge(segment.providerLabel(),
                    new FirstUtterance(segment.startMs(), inputOrdinal),
                    (current, candidate) -> FIRST_UTTERANCE_ORDER.compare(current, candidate) <= 0
                            ? current : candidate);
            inputOrdinal++;
        }

        List<String> referencedLabels = firstUtteranceByLabel.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(FIRST_UTTERANCE_ORDER))
                .map(Map.Entry::getKey)
                .toList();
        Map<String, String> speakerIdByLabel = new HashMap<>();
        List<Speaker> speakers = new ArrayList<>(referencedLabels.size());
        for (int index = 0; index < referencedLabels.size(); index++) {
            String suffix = alphabetic(index);
            String speakerId = "speaker_" + suffix.toLowerCase(java.util.Locale.ROOT);
            speakerIdByLabel.put(referencedLabels.get(index), speakerId);
            speakers.add(new Speaker(speakerId, "Speaker " + suffix));
        }

        orderedSegments.sort(Comparator
                .comparingLong((IndexedSegment indexed) -> indexed.segment().startMs())
                .thenComparingLong(indexed -> indexed.segment().endMs())
                .thenComparingInt(IndexedSegment::inputOrdinal));
        List<TranscriptSegment> segments = new ArrayList<>(orderedSegments.size());
        for (int index = 0; index < orderedSegments.size(); index++) {
            var segment = orderedSegments.get(index).segment();
            segments.add(new TranscriptSegment(
                    String.format(java.util.Locale.ROOT, "seg_%03d", index + 1),
                    speakerIdByLabel.get(segment.providerLabel()),
                    segment.startMs(),
                    segment.endMs(),
                    segment.text()));
        }
        return new TranscriptResult(speakers, segments);
    }

    private static String alphabetic(int zeroBasedIndex) {
        StringBuilder value = new StringBuilder();
        int index = zeroBasedIndex;
        do {
            value.append((char) ('A' + index % 26));
            index = index / 26 - 1;
        } while (index >= 0);
        return value.reverse().toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_RESULT);
    }

    private record IndexedSegment(ProviderTranscriptionResponse.Segment segment, int inputOrdinal) {
    }

    private record FirstUtterance(long startMs, int inputOrdinal) {
    }

    private static final Comparator<FirstUtterance> FIRST_UTTERANCE_ORDER = Comparator
            .comparingLong(FirstUtterance::startMs)
            .thenComparingInt(FirstUtterance::inputOrdinal);
}
