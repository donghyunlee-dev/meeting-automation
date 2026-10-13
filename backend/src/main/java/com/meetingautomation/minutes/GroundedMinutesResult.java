package com.meetingautomation.minutes;

import java.util.List;

/** Provider-neutral claims plus transcript evidence, before canonicalization. */
public record GroundedMinutesResult(
        String templateId,
        String templateVersion,
        List<TextClaim> summaryClaims,
        List<TextClaim> discussionPointClaims,
        List<TextClaim> decisionClaims,
        List<ActionItemClaim> actionItemClaims,
        List<TextClaim> followUpClaims) {

    public record TextClaim(String text, List<String> evidenceSegmentIds) {
        @Override
        public String toString() {
            return "TextClaim[text=<redacted>, evidenceCount="
                    + (evidenceSegmentIds == null ? "invalid" : evidenceSegmentIds.size()) + "]";
        }
    }

    public record ActionItemClaim(String task, List<String> taskEvidenceSegmentIds,
            String ownerParticipantId, List<String> ownerEvidenceSegmentIds,
            String dueDate, List<String> dueDateEvidenceSegmentIds) {
        @Override
        public String toString() {
            return "ActionItemClaim[task=<redacted>, ownerParticipantId=" + ownerParticipantId
                    + ", dueDate=" + dueDate + "]";
        }
    }

    @Override
    public String toString() {
        return "GroundedMinutesResult[templateId=" + templateId + ", templateVersion=" + templateVersion
                + ", summaryClaimCount=" + count(summaryClaims)
                + ", discussionPointClaimCount=" + count(discussionPointClaims)
                + ", decisionClaimCount=" + count(decisionClaims)
                + ", actionItemClaimCount=" + count(actionItemClaims)
                + ", followUpClaimCount=" + count(followUpClaims) + "]";
    }

    private static String count(List<?> values) {
        return values == null ? "invalid" : Integer.toString(values.size());
    }
}
