package com.meetingautomation.document;

/** A diarized speaker before the user maps it to a meeting participant. */
public record Speaker(String speakerId, String label, String participantId) {
    public Speaker(String speakerId, String label) {
        this(speakerId, label, null);
    }

    public Speaker withParticipantId(String newParticipantId) {
        return new Speaker(speakerId, label, newParticipantId);
    }

    @Override
    public String toString() {
        return "Speaker[speakerId=" + speakerId + ", label=<redacted>, participantId=" + participantId + "]";
    }
}
