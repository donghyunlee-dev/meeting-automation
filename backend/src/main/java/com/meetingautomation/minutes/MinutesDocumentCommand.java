package com.meetingautomation.minutes;

/** Final reviewed minutes and the chosen template; audio and transcript are deliberately excluded. */
public record MinutesDocumentCommand(String templateContent, String reviewedMinutes) {
    public MinutesDocumentCommand {
        if (templateContent == null || templateContent.isBlank() || reviewedMinutes == null || reviewedMinutes.isBlank())
            throw new IllegalArgumentException("Minutes document input is invalid.");
    }

    @Override public String toString() {
        return "MinutesDocumentCommand[templateContent=<redacted>, reviewedMinutes=<redacted>]";
    }
}
