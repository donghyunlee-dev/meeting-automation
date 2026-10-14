package com.meetingautomation.api.session;

import com.meetingautomation.document.Speaker;
import com.meetingautomation.document.StructuredMinutes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Collects stable, value-free semantic issues from one immutable Review snapshot. */
@Component
public final class ReviewConfirmationValidator {
    public List<ReviewValidationIssue> validate(MeetingSession session) {
        List<ReviewValidationIssue> issues = new ArrayList<>();
        List<String> rosterIds = session.meeting() == null ? List.of() : session.meeting().participantIds();
        for (int index = 0; index < session.speakers().size(); index++) {
            Speaker speaker = session.speakers().get(index);
            String path = "speakers[" + index + "].participantId";
            String participantId = speaker.participantId();
            if (participantId == null || participantId.isBlank()) {
                issues.add(new ReviewValidationIssue(path, "SPEAKER_UNMAPPED"));
            } else if (!rosterIds.contains(participantId)) {
                issues.add(new ReviewValidationIssue(path, "PARTICIPANT_OUTSIDE_SESSION_ROSTER"));
            }
        }
        validateMinutes(session.minutes(), session.meeting(), rosterIds, issues);
        return List.copyOf(issues);
    }

    private static void validateMinutes(
            StructuredMinutes minutes, MeetingSession.Meeting meeting, List<String> rosterIds,
            List<ReviewValidationIssue> issues) {
        if (minutes == null) {
            issues.add(new ReviewValidationIssue("minutes", "REQUIRED"));
            return;
        }
        required(minutes.templateId(), "minutes.templateId", issues);
        required(minutes.templateVersion(), "minutes.templateVersion", issues);
        if (minutes.templateId() != null && !minutes.templateId().isBlank()
                && !Objects.equals(minutes.templateId(), meeting == null ? null : meeting.templateId())) {
            issues.add(new ReviewValidationIssue("minutes.templateId", "TEMPLATE_MISMATCH"));
        }
        if (minutes.templateVersion() != null && !minutes.templateVersion().isBlank()
                && !Objects.equals(minutes.templateVersion(), meeting == null ? null : meeting.templateVersion())) {
            issues.add(new ReviewValidationIssue("minutes.templateVersion", "TEMPLATE_MISMATCH"));
        }
        if (minutes.summary() == null) issues.add(new ReviewValidationIssue("minutes.summary", "REQUIRED"));
        validateTextList(minutes.discussionPoints(), "minutes.discussionPoints", issues);
        validateTextList(minutes.decisions(), "minutes.decisions", issues);
        validateActionItems(minutes.actionItems(), rosterIds, issues);
        validateTextList(minutes.followUps(), "minutes.followUps", issues);
    }

    private static void required(String value, String path, List<ReviewValidationIssue> issues) {
        if (value == null || value.isBlank()) issues.add(new ReviewValidationIssue(path, "REQUIRED"));
    }

    private static void validateTextList(List<?> values, String path, List<ReviewValidationIssue> issues) {
        if (values == null) {
            issues.add(new ReviewValidationIssue(path, "INVALID_TYPE"));
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            String itemPath = path + "[" + index + "]";
            if (!(value instanceof String text)) {
                issues.add(new ReviewValidationIssue(itemPath, value == null ? "REQUIRED" : "INVALID_TYPE"));
            } else if (text.isBlank()) {
                issues.add(new ReviewValidationIssue(itemPath, "BLANK_VALUE"));
            }
        }
    }

    private static void validateActionItems(
            List<?> items, List<String> rosterIds, List<ReviewValidationIssue> issues) {
        if (items == null) {
            issues.add(new ReviewValidationIssue("minutes.actionItems", "INVALID_TYPE"));
            return;
        }
        for (int index = 0; index < items.size(); index++) {
            Object value = items.get(index);
            String path = "minutes.actionItems[" + index + "]";
            if (!(value instanceof StructuredMinutes.ActionItem item)) {
                issues.add(new ReviewValidationIssue(path, value == null ? "REQUIRED" : "INVALID_TYPE"));
                continue;
            }
            if (item.ownerParticipantId() != null && !rosterIds.contains(item.ownerParticipantId())) {
                issues.add(new ReviewValidationIssue(path + ".ownerParticipantId",
                        "PARTICIPANT_OUTSIDE_SESSION_ROSTER"));
            }
            if (item.task() == null) issues.add(new ReviewValidationIssue(path + ".task", "REQUIRED"));
            else if (item.task().isBlank()) issues.add(new ReviewValidationIssue(path + ".task", "BLANK_VALUE"));
        }
    }
}
