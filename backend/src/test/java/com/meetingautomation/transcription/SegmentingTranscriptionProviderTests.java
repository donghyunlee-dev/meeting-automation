package com.meetingautomation.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentingTranscriptionProviderTests {
    @TempDir Path directory;

    @Test
    void splitsOversizedRecordingAndReturnsMeetingRelativeSegmentsWithoutConflatingSpeakerLabels() throws Exception {
        Path first = Files.writeString(directory.resolve("first.mp3"), "one");
        Path second = Files.writeString(directory.resolve("second.mp3"), "two");
        List<Long> requestDurations = new ArrayList<>();
        TranscriptionProvider delegate = command -> {
            requestDurations.add(command.meetingDurationMs());
            return new ProviderTranscriptionResponse(List.of(new ProviderTranscriptionResponse.Speaker("A")),
                    List.of(new ProviderTranscriptionResponse.Segment("seg", "A", 100, 900, "hello")));
        };
        AudioSegmenter segmenter = (command, maxBytes) -> new AudioSegmenter.SplitAudio(
                List.of(new AudioSegmenter.Segment(first, 0, 1_000),
                        new AudioSegmenter.Segment(second, 1_000, 1_000)), () -> {});
        var provider = new SegmentingTranscriptionProvider(delegate, segmenter, 4);

        var result = provider.transcribeWithDiarization(
                new TranscriptionCommand(new ByteArrayInputStream(new byte[5]), 5, "audio/webm", "ko", 2_000));

        assertEquals(List.of(1_250L, 1_250L), requestDurations);
        assertEquals(List.of(100L, 1_100L), result.segments().stream().map(ProviderTranscriptionResponse.Segment::startMs).toList());
        assertEquals(2, result.speakers().size());
        assertEquals(false, result.segments().get(0).providerLabel().equals(result.segments().get(1).providerLabel()));
    }

    @Test
    void sendsSmallRecordingDirectlyWithoutSplitting() {
        AudioSegmenter segmenter = (command, maxBytes) -> { throw new AssertionError("split called"); };
        TranscriptionProvider delegate = command -> new ProviderTranscriptionResponse(List.of(), List.of());
        var provider = new SegmentingTranscriptionProvider(delegate, segmenter, 4);

        assertEquals(List.of(), provider.transcribeWithDiarization(
                new TranscriptionCommand(new ByteArrayInputStream(new byte[4]), 4, "audio/webm", "ko", 1_000)).segments());
    }

    @Test
    void doesNotReturnPartialTranscriptWhenAnySegmentFails() throws Exception {
        Path first = Files.writeString(directory.resolve("first.mp3"), "one");
        Path second = Files.writeString(directory.resolve("second.mp3"), "two");
        AudioSegmenter segmenter = (command, maxBytes) -> new AudioSegmenter.SplitAudio(
                List.of(new AudioSegmenter.Segment(first, 0, 1_000),
                        new AudioSegmenter.Segment(second, 1_000, 1_000)), () -> {});
        int[] calls = {0};
        TranscriptionProvider delegate = command -> {
            if (++calls[0] == 2) throw new TranscriptionException(TranscriptionException.Code.PROCESSING_FAILED, true);
            return new ProviderTranscriptionResponse(List.of(), List.of());
        };

        var failure = assertThrows(TranscriptionException.class, () ->
                new SegmentingTranscriptionProvider(delegate, segmenter, 4).transcribeWithDiarization(
                        new TranscriptionCommand(new ByteArrayInputStream(new byte[5]), 5, "audio/webm", "ko", 2_000)));
        assertEquals(true, failure.retryable());
        assertEquals(2, calls[0]);
    }

    @Test
    void clipsEncoderPaddingAtSegmentBoundaryWithoutRejectingValidSpeech() throws Exception {
        Path first = Files.writeString(directory.resolve("first.mp3"), "one");
        AudioSegmenter segmenter = (command, maxBytes) -> new AudioSegmenter.SplitAudio(
                List.of(new AudioSegmenter.Segment(first, 0, 1_000)), () -> {});
        TranscriptionProvider delegate = command -> {
            assertEquals(1_250, command.meetingDurationMs());
            return new ProviderTranscriptionResponse(List.of(new ProviderTranscriptionResponse.Speaker("A")),
                    List.of(new ProviderTranscriptionResponse.Segment("end", "A", 950, 1_050, "end")));
        };

        var result = new SegmentingTranscriptionProvider(delegate, segmenter, 3)
                .transcribeWithDiarization(new TranscriptionCommand(
                        new ByteArrayInputStream(new byte[4]), 4, "audio/webm", "ko", 1_000));

        assertEquals(1_000, result.segments().getFirst().endMs());
    }
}
