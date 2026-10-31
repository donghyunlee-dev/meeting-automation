package com.meetingautomation.transcription;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

public record ProviderTranscriptionResponse(List<Speaker> speakers, List<Segment> segments) {
    public static final long MAX_BOUNDARY_DRIFT_MS = 250;

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

    /** Clips small provider timestamp overruns to the recorded meeting boundary. */
    public ProviderTranscriptionResponse reconcileToMeetingDuration(long meetingDurationMs) {
        if (meetingDurationMs < 0) throw invalid();
        List<Segment> reconciled = new java.util.ArrayList<>(segments.size());
        boolean changed = false;
        for (Segment segment : segments) {
            if (segment.startMs() > meetingDurationMs
                    || segment.endMs() - meetingDurationMs > MAX_BOUNDARY_DRIFT_MS) {
                throw invalid();
            }
            long endMs = Math.min(segment.endMs(), meetingDurationMs);
            if (endMs < segment.startMs()) throw invalid();
            changed |= endMs != segment.endMs();
            reconciled.add(endMs == segment.endMs() ? segment
                    : new Segment(segment.providerSegmentId(), segment.providerLabel(),
                            segment.startMs(), endMs, segment.text()));
        }
        return changed ? new ProviderTranscriptionResponse(speakers, reconciled) : this;
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
