package com.meetingautomation.document;

public record TranscriptSegment(String segmentId, String speakerId, long startMs, long endMs, String text) {
    @Override
    public String toString() {
        return "TranscriptSegment[segmentId=" + segmentId + ", speakerId=" + speakerId
                + ", startMs=" + startMs + ", endMs=" + endMs + ", text=<redacted>]";
    }
}
