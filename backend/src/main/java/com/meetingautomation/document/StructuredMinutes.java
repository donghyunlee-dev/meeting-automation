package com.meetingautomation.document;

import java.time.LocalDate;
import java.util.List;

public record StructuredMinutes(
        String templateId,
        String templateVersion,
        String summary,
        List<String> discussionPoints,
        List<String> decisions,
        List<ActionItem> actionItems,
        List<String> followUps) {

    public StructuredMinutes {
        discussionPoints = List.copyOf(discussionPoints);
        decisions = List.copyOf(decisions);
        actionItems = List.copyOf(actionItems);
        followUps = List.copyOf(followUps);
    }

    public record ActionItem(String ownerParticipantId, String task, LocalDate dueDate) {
    }
}
