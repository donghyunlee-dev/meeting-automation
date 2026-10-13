package com.meetingautomation.transcription;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

public record ProviderTranscriptionResponse(List<Speaker> speakers, List<Segment> segments) {
    public ProviderTranscriptionResponse {
        if (speakers == null || segments == null) throw invalid();
        try {
            speakers = List.copyOf(speakers);
            segments = List.copyOf(segments);
        } catch (NullPointerException invalidContents) {
            throw invalid();
        }

        Set<String> labels = new HashSet<>();
        for (Speaker speaker : speakers) {
            if (speaker.providerLabel() == null || speaker.providerLabel().isBlank()
                    || !labels.add(speaker.providerLabel())) {
                throw invalid();
            }
        }
        for (Segment segment : segments) {
            if (segment.providerLabel() == null || !labels.contains(segment.providerLabel())
                    || segment.startMs() < 0 || segment.endMs() < segment.startMs()
                    || segment.text() == null || segment.text().isBlank()) {
                throw invalid();
            }
        }
    }

    public void validateMeetingDuration(long meetingDurationMs) {
        if (meetingDurationMs < 0 || segments.stream().anyMatch(segment -> segment.endMs() > meetingDurationMs)) {
            throw invalid();
        }
    }

    @Override
    public String toString() {
        return "ProviderTranscriptionResponse[speakers=" + speakers.size() + ", segments=" + segments.size() + "]";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Provider transcription response is invalid.");
    }

    public record Speaker(String providerLabel) {
        @Override
        public String toString() {
            return "Speaker[providerLabel=<redacted>]";
        }
    }

    public record Segment(String providerSegmentId, String providerLabel, long startMs, long endMs, String text) {
        @Override
        public String toString() {
            return "Segment[providerSegmentId=<redacted>, providerLabel=<redacted>, startMs=" + startMs
                    + ", endMs=" + endMs + ", text=<redacted>]";
        }
    }
}
