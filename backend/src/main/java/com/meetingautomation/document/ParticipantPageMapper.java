package com.meetingautomation.document;

import java.util.regex.Pattern;

/** Converts provider page fields into the public participant model without retaining source content. */
public final class ParticipantPageMapper {
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private ParticipantPageMapper() { }

    public static Participant map(String id, String title, String bodyText) {
        if (isBlank(id) || isBlank(title) || bodyText == null) {
            throw DocumentProviderException.participantListFailed(false);
        }
        String email = firstEmailLine(bodyText);
        if (email == null || !EMAIL_ADDRESS.matcher(email).matches()) {
            throw DocumentProviderException.participantListFailed(false);
        }
        return new Participant(id, title.trim(), email);
    }

    private static String firstEmailLine(String bodyText) {
        for (String line : bodyText.split("\\R", -1)) {
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("Email:")) {
                return trimmedLine.substring("Email:".length()).trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
