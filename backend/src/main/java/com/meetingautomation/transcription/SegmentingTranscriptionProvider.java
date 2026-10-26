package com.meetingautomation.transcription;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps each provider request below its file limit while preserving meeting-relative timestamps. */
final class SegmentingTranscriptionProvider implements TranscriptionProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentingTranscriptionProvider.class);
    private static final long SAFE_PROVIDER_BYTES = 24_000_000;
    private static final long ENCODER_PADDING_MS = 250;

    private final TranscriptionProvider delegate;
    private final AudioSegmenter segmenter;
    private final long maxSegmentBytes;

    SegmentingTranscriptionProvider(TranscriptionProvider delegate, AudioSegmenter segmenter, long configuredMaxBytes) {
        this.delegate = delegate;
        this.segmenter = segmenter;
        this.maxSegmentBytes = Math.min(configuredMaxBytes, SAFE_PROVIDER_BYTES);
    }

    @Override
    public ProviderTranscriptionResponse transcribeWithDiarization(TranscriptionCommand command) {
        if (command == null) throw new TranscriptionException(TranscriptionException.Code.INVALID_AUDIO, false);
        long bytes = command.audioStream() == null ? fileSize(command) : command.audioBytes();
        if (bytes <= maxSegmentBytes) return delegate.transcribeWithDiarization(command);

        LOGGER.info("Splitting audio for transcription traceId={} audioBytes={} maxSegmentBytes={} meetingDurationMs={}",
                command.traceId(), bytes, maxSegmentBytes, command.meetingDurationMs());
        try (AudioSegmenter.SplitAudio split = segmenter.split(command, maxSegmentBytes)) {
            if (split.segments().isEmpty()) throw new TranscriptionException(TranscriptionException.Code.INVALID_AUDIO, false);
            List<ProviderTranscriptionResponse.Segment> segments = new ArrayList<>();
            Set<String> speakerLabels = new LinkedHashSet<>();
            for (int index = 0; index < split.segments().size(); index++) {
                AudioSegmenter.Segment part = split.segments().get(index);
                long partBytes = Files.size(part.path());
                if (partBytes < 1 || partBytes > maxSegmentBytes || part.durationMs() < 1
                        || part.startMs() < 0 || part.startMs() >= command.meetingDurationMs()) {
                    throw new TranscriptionException(TranscriptionException.Code.INVALID_AUDIO, false);
                }
                long startedAt = System.nanoTime();
                LOGGER.info("Transcription segment started traceId={} segment={} segmentCount={} segmentBytes={} segmentStartMs={} segmentDurationMs={}",
                        command.traceId(), index + 1, split.segments().size(), partBytes, part.startMs(), part.durationMs());
                try {
                    var result = delegate.transcribeWithDiarization(new TranscriptionCommand(
                            part.path(), "audio/mpeg", command.languageHint(),
                            Math.addExact(part.durationMs(), ENCODER_PADDING_MS), command.traceId()));
                    String prefix = "part" + index + ":";
                    long partEnd = Math.min(command.meetingDurationMs(), Math.addExact(part.startMs(), part.durationMs()));
                    for (var speaker : result.speakers()) speakerLabels.add(prefix + speaker.providerLabel());
                    for (var segment : result.segments()) {
                        long startMs = Math.addExact(part.startMs(), segment.startMs());
                        if (startMs >= partEnd) continue;
                        segments.add(new ProviderTranscriptionResponse.Segment(
                                segment.providerSegmentId() == null ? null : prefix + segment.providerSegmentId(),
                                prefix + segment.providerLabel(), startMs,
                                Math.min(partEnd, Math.addExact(part.startMs(), segment.endMs())), segment.text()));
                    }
                    LOGGER.info("Transcription segment succeeded traceId={} segment={} segmentCount={} elapsedMs={}",
                            command.traceId(), index + 1, split.segments().size(), elapsedMs(startedAt));
                } catch (TranscriptionException failure) {
                    LOGGER.warn("Transcription segment failed traceId={} segment={} segmentCount={} elapsedMs={} errorCode={} retryable={}",
                            command.traceId(), index + 1, split.segments().size(), elapsedMs(startedAt),
                            failure.code(), failure.retryable());
                    throw failure;
                }
            }
            var speakers = speakerLabels.stream().map(ProviderTranscriptionResponse.Speaker::new).toList();
            var response = new ProviderTranscriptionResponse(speakers, segments);
            response.validateMeetingDuration(command.meetingDurationMs());
            return response;
        } catch (IOException | ArithmeticException invalidSegment) {
            throw new TranscriptionException(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
    }

    private static long fileSize(TranscriptionCommand command) {
        try { return Files.size(command.audioReference()); }
        catch (IOException | RuntimeException failure) {
            throw new TranscriptionException(TranscriptionException.Code.INVALID_AUDIO, false);
        }
    }

    private static long elapsedMs(long startedAt) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt); }
}
