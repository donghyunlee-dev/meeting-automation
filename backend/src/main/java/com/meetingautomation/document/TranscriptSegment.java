package com.meetingautomation.document;

public record TranscriptSegment(String segmentId, String speakerId, long startMs, long endMs, String text) {
}
