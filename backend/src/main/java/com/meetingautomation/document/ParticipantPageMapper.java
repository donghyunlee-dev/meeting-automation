package com.meetingautomation.document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts provider page fields into the public participant model without retaining source content. */
public final class ParticipantPageMapper {
    private static final Pattern EMAIL_LINE = Pattern.compile("(?m)^\\s*Email:\\s*(\\S+)\\s*$");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private ParticipantPageMapper() { }

    public static Participant map(String id, String title, String bodyText) {
        if (isBlank(id) || isBlank(title) || bodyText == null) {
            throw DocumentProviderException.participantListFailed(false);
        }
        Matcher emailLine = EMAIL_LINE.matcher(bodyText);
        if (!emailLine.find() || !EMAIL_ADDRESS.matcher(emailLine.group(1)).matches()) {
            throw DocumentProviderException.participantListFailed(false);
        }
        return new Participant(id, title.trim(), emailLine.group(1));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
