package com.meetingautomation.email;

import java.util.List;

public record MeetingEmailCommand(String subject, String textBody, String documentUrl, List<Recipient> recipients) {
    public MeetingEmailCommand {
        recipients = List.copyOf(recipients);
    }

    @Override
    public String toString() {
        return "MeetingEmailCommand[recipientCount=" + recipients.size() + "]";
    }

    public record Recipient(String participantId, String email) {
        @Override
        public String toString() {
            return "Recipient[participantId=" + participantId + "]";
        }
    }
}
