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

    @Override
    public String toString() {
        return "StructuredMinutes[templateId=" + templateId + ", templateVersion=" + templateVersion
                + ", summary=<redacted>, discussionPointCount=" + discussionPoints.size()
                + ", decisionCount=" + decisions.size() + ", actionItemCount=" + actionItems.size()
                + ", followUpCount=" + followUps.size() + "]";
    }

    public record ActionItem(String ownerParticipantId, String task, LocalDate dueDate) {
        @Override
        public String toString() {
            return "ActionItem[ownerParticipantId=" + ownerParticipantId
                    + ", task=<redacted>, dueDate=" + dueDate + "]";
        }
    }
}
