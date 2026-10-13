package com.meetingautomation.minutes;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates provider claim structure and removes only claims not supported by the input Transcript. */
@Component
public final class GroundedMinutesResultNormalizer {
    public StructuredMinutes normalize(MinutesCommand command, GroundedMinutesResult result) {
        validateCommand(command);
        validateResultHeader(command, result);
        Map<String, TranscriptSegment> segments = segmentIndex(command.transcript());
        Set<String> rosterIds = participantIds(command.participants());
        validateMappings(command.speakerParticipantMappings(), command.transcript(), rosterIds);

        String summary = normalizeTextClaims(result.summaryClaims(), segments).stream()
                .reduce((left, right) -> left + "\n" + right).orElse("");
        List<String> discussionPoints = normalizeTextClaims(result.discussionPointClaims(), segments);
        List<String> decisions = normalizeTextClaims(result.decisionClaims(), segments);
        List<String> followUps = normalizeTextClaims(result.followUpClaims(), segments);
        List<StructuredMinutes.ActionItem> actionItems = normalizeActionClaims(
                result.actionItemClaims(), segments, rosterIds, command.speakerParticipantMappings());

        return new StructuredMinutes(command.templateId(), command.templateVersion(), summary,
                discussionPoints, decisions, actionItems, followUps);
    }

    private static void validateCommand(MinutesCommand command) {
        if (command == null || blank(command.templateId()) || blank(command.templateVersion())
                || blank(command.templateContent()) || command.participants() == null
                || command.speakerParticipantMappings() == null || command.transcript() == null) {
            throw invalidResult();
        }
    }

    private static void validateResultHeader(MinutesCommand command, GroundedMinutesResult result) {
        if (result == null || blank(result.templateId()) || blank(result.templateVersion())
                || !command.templateId().equals(result.templateId())
                || !command.templateVersion().equals(result.templateVersion())
                || result.summaryClaims() == null || result.discussionPointClaims() == null
                || result.decisionClaims() == null || result.actionItemClaims() == null
                || result.followUpClaims() == null) {
            throw invalidResult();
        }
    }

    private static Map<String, TranscriptSegment> segmentIndex(List<TranscriptSegment> transcript) {
        Map<String, TranscriptSegment> segments = new HashMap<>();
        Set<String> speakerIds = new HashSet<>();
        for (TranscriptSegment segment : transcript) {
            if (segment == null || blank(segment.segmentId()) || blank(segment.speakerId())
                    || segment.text() == null || segment.text().isBlank()
                    || segment.startMs() < 0 || segment.endMs() < segment.startMs()
                    || segments.putIfAbsent(segment.segmentId(), segment) != null) {
                throw invalidResult();
            }
            speakerIds.add(segment.speakerId());
        }
        return Map.copyOf(segments);
    }

    private static Set<String> participantIds(List<MeetingSession.ParticipantReference> participants) {
        Set<String> ids = new HashSet<>();
        for (MeetingSession.ParticipantReference participant : participants) {
            if (participant == null || blank(participant.id()) || blank(participant.name())
                    || !ids.add(participant.id())) {
                throw invalidResult();
            }
        }
        return Set.copyOf(ids);
    }

    private static void validateMappings(Map<String, String> mappings,
            List<TranscriptSegment> transcript, Set<String> rosterIds) {
        Set<String> speakerIds = new HashSet<>();
        transcript.forEach(segment -> speakerIds.add(segment.speakerId()));
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            if (blank(mapping.getKey()) || !speakerIds.contains(mapping.getKey())
                    || blank(mapping.getValue()) || !rosterIds.contains(mapping.getValue())) {
                throw invalidResult();
            }
        }
    }

    private static List<String> normalizeTextClaims(List<GroundedMinutesResult.TextClaim> claims,
            Map<String, TranscriptSegment> segments) {
        List<String> normalized = new ArrayList<>();
        for (GroundedMinutesResult.TextClaim claim : claims) {
            if (claim == null || blank(claim.text())) throw invalidResult();
            List<String> evidence = validatedEvidence(claim.evidenceSegmentIds());
            if (isGrounded(evidence, segments)) normalized.add(claim.text());
        }
        return List.copyOf(normalized);
    }

    private static List<StructuredMinutes.ActionItem> normalizeActionClaims(
            List<GroundedMinutesResult.ActionItemClaim> claims,
            Map<String, TranscriptSegment> segments, Set<String> rosterIds,
            Map<String, String> speakerParticipantMappings) {
        List<StructuredMinutes.ActionItem> normalized = new ArrayList<>();
        for (GroundedMinutesResult.ActionItemClaim claim : claims) {
            if (claim == null || blank(claim.task())) throw invalidResult();
            List<String> taskEvidence = validatedEvidence(claim.taskEvidenceSegmentIds());
            List<String> ownerEvidence = validatedEvidence(claim.ownerEvidenceSegmentIds());
            List<String> dateEvidence = validatedEvidence(claim.dueDateEvidenceSegmentIds());
            validateOwner(claim.ownerParticipantId(), rosterIds);
            LocalDate date = parseDate(claim.dueDate());

            if (!isGrounded(taskEvidence, segments)) continue;
            String owner = supportedOwner(claim.ownerParticipantId(), ownerEvidence,
                    segments, speakerParticipantMappings) ? claim.ownerParticipantId() : null;
            LocalDate supportedDate = date != null && isDateCited(date, dateEvidence, segments) ? date : null;
            normalized.add(new StructuredMinutes.ActionItem(owner, claim.task(), supportedDate));
        }
        return List.copyOf(normalized);
    }

    private static List<String> validatedEvidence(List<String> evidence) {
        if (evidence == null || evidence.stream().anyMatch(GroundedMinutesResultNormalizer::blank)) {
            throw invalidResult();
        }
        return evidence;
    }

    private static boolean isGrounded(List<String> evidence, Map<String, TranscriptSegment> segments) {
        return !evidence.isEmpty() && evidence.stream().allMatch(segments::containsKey);
    }

    private static boolean isDateCited(LocalDate date, List<String> evidence,
            Map<String, TranscriptSegment> segments) {
        String canonicalDate = date.toString();
        return isGrounded(evidence, segments)
                && evidence.stream().anyMatch(segmentId -> segments.get(segmentId).text().contains(canonicalDate));
    }

    private static void validateOwner(String ownerParticipantId, Set<String> rosterIds) {
        if (ownerParticipantId != null
                && (blank(ownerParticipantId) || !rosterIds.contains(ownerParticipantId))) {
            throw invalidResult();
        }
    }

    private static boolean supportedOwner(String ownerParticipantId, List<String> evidence,
            Map<String, TranscriptSegment> segments, Map<String, String> mappings) {
        if (ownerParticipantId == null || !isGrounded(evidence, segments)) return false;
        return evidence.stream().allMatch(segmentId -> {
            TranscriptSegment segment = segments.get(segmentId);
            return ownerParticipantId.equals(mappings.get(segment.speakerId()));
        });
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalidResult();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalidDate) {
            throw invalidResult();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalidResult() {
        return new IllegalArgumentException("Minutes result is invalid.");
    }
}
