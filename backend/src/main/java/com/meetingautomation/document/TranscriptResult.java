package com.meetingautomation.document;

import java.util.List;

/** Standard, provider-neutral result of transcription and diarization. */
public record TranscriptResult(List<Speaker> speakers, List<TranscriptSegment> segments) {
    public TranscriptResult {
        if (speakers == null || segments == null) {
            throw new IllegalArgumentException("Transcript result is invalid.");
        }
        speakers = List.copyOf(speakers);
        segments = List.copyOf(segments);
    }

    @Override
    public String toString() {
        return "TranscriptResult[speakers=" + speakers.size() + ", segments=" + segments.size() + "]";
    }
}
