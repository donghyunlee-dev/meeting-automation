package com.meetingautomation.minutes;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.document.TranscriptSegment;
import java.util.List;
import java.util.Map;

/** Minimum application context passed to a Minutes provider. It has no audio, email, or credentials. */
public record MinutesCommand(
        String templateId,
        String templateVersion,
        String templateContent,
        String meetingTitle,
        String meetingAt,
        String timezone,
        List<MeetingSession.ParticipantReference> participants,
        Map<String, String> speakerParticipantMappings,
        List<TranscriptSegment> transcript) {

    public MinutesCommand {
        participants = List.copyOf(participants);
        speakerParticipantMappings = Map.copyOf(speakerParticipantMappings);
        transcript = List.copyOf(transcript);
    }

    public MinutesCommand(String templateId, String templateVersion, String templateContent,
            List<MeetingSession.ParticipantReference> participants, Map<String, String> speakerParticipantMappings,
            List<TranscriptSegment> transcript) {
        this(templateId, templateVersion, templateContent, null, null, null,
                participants, speakerParticipantMappings, transcript);
    }

    @Override
    public String toString() {
        return "MinutesCommand[templateId=" + templateId + ", templateVersion=" + templateVersion
                + ", templateContent=<redacted>, participantCount=" + participants.size()
                + ", speakerMappingCount=" + speakerParticipantMappings.size()
                + ", transcriptSegmentCount=" + transcript.size() + "]";
    }
}
