package com.meetingautomation.transcription;

import java.nio.file.Path;
import java.util.List;

@FunctionalInterface
interface AudioSegmenter {
    SplitAudio split(TranscriptionCommand command, long maxSegmentBytes);

    record Segment(Path path, long startMs, long durationMs) { }

    record SplitAudio(List<Segment> segments, Runnable cleanup) implements AutoCloseable {
        @Override public void close() { cleanup.run(); }
    }
}
