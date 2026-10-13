package com.meetingautomation.api.session;

import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.TranscriptSegment;
import java.util.List;

public record MeetingSession(
        String sessionId,
        int version,
        String status,
        long documentConnectionVersion,
        Meeting meeting,
        String processingStage,
        int progressPercent,
        List<Speaker> speakers,
        List<TranscriptSegment> transcript,
        ProcessingFailure processingFailure) {
    public MeetingSession {
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("Meeting session progress is invalid.");
        }
        speakers = List.copyOf(speakers);
        transcript = List.copyOf(transcript);
    }

    public MeetingSession(String sessionId, int version, String status,
            long documentConnectionVersion, Meeting meeting) {
        this(sessionId, version, status, documentConnectionVersion, meeting,
                null, 0, List.of(), List.of(), null);
    }

    @Override
    public String toString() {
        return "MeetingSession[sessionId=" + sessionId + ", version=" + version + ", status=" + status
                + ", processingStage=" + processingStage + ", progressPercent=" + progressPercent
                + ", speakers=" + speakers.size() + ", transcriptSegments=" + transcript.size() + "]";
    }

    public record Meeting(String title, String templateId, List<String> participantIds, String timezone) {
        public Meeting {
            participantIds = List.copyOf(participantIds);
        }
    }
}
