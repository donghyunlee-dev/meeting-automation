package com.meetingautomation.api.session;

import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
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
        ProcessingFailure processingFailure,
        StructuredMinutes minutes) {
    public MeetingSession {
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("Meeting session progress is invalid.");
        }
        speakers = List.copyOf(speakers);
        transcript = List.copyOf(transcript);
    }

    public MeetingSession(String sessionId, int version, String status,
            long documentConnectionVersion, Meeting meeting, String processingStage, int progressPercent,
            List<Speaker> speakers, List<TranscriptSegment> transcript, ProcessingFailure processingFailure) {
        this(sessionId, version, status, documentConnectionVersion, meeting, processingStage, progressPercent,
                speakers, transcript, processingFailure, null);
    }

    public MeetingSession(String sessionId, int version, String status,
            long documentConnectionVersion, Meeting meeting) {
        this(sessionId, version, status, documentConnectionVersion, meeting,
                null, 0, List.of(), List.of(), null, null);
    }

    public MeetingSession withParticipantReferences(List<ParticipantReference> references) {
        Meeting updatedMeeting = new Meeting(meeting.title(), meeting.templateId(), meeting.templateVersion(),
                meeting.participantIds(),
                meeting.timezone(), references);
        return new MeetingSession(sessionId, version, status, documentConnectionVersion, updatedMeeting,
                processingStage, progressPercent, speakers, transcript, processingFailure, minutes);
    }

    public MeetingSession withTemplateVersion(String templateVersion) {
        Meeting updatedMeeting = new Meeting(meeting.title(), meeting.templateId(), templateVersion,
                meeting.participantIds(), meeting.timezone(), meeting.participantReferences());
        return new MeetingSession(sessionId, version, status, documentConnectionVersion, updatedMeeting,
                processingStage, progressPercent, speakers, transcript, processingFailure, minutes);
    }

    @Override
    public String toString() {
        return "MeetingSession[sessionId=" + sessionId + ", version=" + version + ", status=" + status
                + ", processingStage=" + processingStage + ", progressPercent=" + progressPercent
                + ", speakers=" + speakers.size() + ", transcriptSegments=" + transcript.size()
                + ", hasMinutes=" + (minutes != null) + "]";
    }

    public record Meeting(String title, String templateId, String templateVersion,
            List<String> participantIds, String timezone,
            List<ParticipantReference> participantReferences) {
        public Meeting {
            participantIds = List.copyOf(participantIds);
            participantReferences = List.copyOf(participantReferences);
            for (ParticipantReference reference : participantReferences) {
                if (reference == null || reference.id() == null || reference.id().isBlank()
                        || reference.name() == null || reference.name().isBlank()
                        || !participantIds.contains(reference.id())) {
                    throw new IllegalArgumentException("Meeting participant references are invalid.");
                }
            }
            if (participantReferences.stream().map(ParticipantReference::id).distinct().count()
                    != participantReferences.size()) {
                throw new IllegalArgumentException("Meeting participant references are invalid.");
            }
        }

        public Meeting(String title, String templateId, List<String> participantIds,
                String timezone, List<ParticipantReference> participantReferences) {
            this(title, templateId, null, participantIds, timezone, participantReferences);
        }

        public Meeting(String title, String templateId, List<String> participantIds, String timezone) {
            this(title, templateId, null, participantIds, timezone, List.of());
        }

        @Override
        public String toString() {
            return "Meeting[title=" + title + ", templateId=" + templateId
                    + ", participantCount=" + participantIds.size() + ", timezone=" + timezone + "]";
        }
    }

    public record ParticipantReference(String id, String name) {
        public ParticipantReference {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Meeting participant reference is invalid.");
            }
        }

        @Override
        public String toString() {
            return "ParticipantReference[id=" + id + ", name=<redacted>]";
        }
    }
}
