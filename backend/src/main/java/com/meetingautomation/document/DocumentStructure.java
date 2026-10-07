package com.meetingautomation.document;

public record DocumentStructure(String rootId, String meetingsPageId, String participantsPageId) {
    public DocumentStructure {
        if (isBlank(rootId) || isBlank(meetingsPageId) || isBlank(participantsPageId)) {
            throw new IllegalArgumentException("Document structure identifiers are required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
