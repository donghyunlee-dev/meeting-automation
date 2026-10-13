package com.meetingautomation.minutes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroundedMinutesResultNormalizerTests {
    private static final List<TranscriptSegment> TRANSCRIPT = List.of(
            new TranscriptSegment("seg_001", "speaker_a", 0, 500, "The release is scheduled for Friday."),
            new TranscriptSegment("seg_002", "speaker_b", 500, 900, "We will review the migration."));

    private final GroundedMinutesResultNormalizer normalizer = new GroundedMinutesResultNormalizer();

    @Test
    void retainsGroundedClaimsAndDropsOnlyTextClaimsWithEmptyOrUnknownEvidence() {
        GroundedMinutesResult result = new GroundedMinutesResult(
                "default.md", "1.0.0",
                List.of(claim("Grounded summary", "seg_001"), claim("Unsupported summary", "missing"),
                        claim("Second grounded summary", "seg_002")),
                List.of(claim("Migration was discussed", "seg_002"), claim("Uncited discussion", "")),
                List.of(claim("Review was agreed", "seg_002")),
                List.of(action("Review the migration", List.of("seg_002"), null, List.of(), null, List.of()),
                        action("Unsupported action", List.of(), null, List.of(), null, List.of())),
                List.of(claim("Check the result", "seg_001"), claim("Unsupported follow-up", "missing")));

        StructuredMinutes normalized = normalizer.normalize(command(TRANSCRIPT), result);

        assertThat(normalized).isEqualTo(new StructuredMinutes("default.md", "1.0.0",
                "Grounded summary\nSecond grounded summary", List.of("Migration was discussed"),
                List.of("Review was agreed"),
                List.of(new StructuredMinutes.ActionItem(null, "Review the migration", null)),
                List.of("Check the result")));
    }

    @Test
    void keepsGroundedActionAndClearsUnsupportedOwnerAndDueDateIndependently() {
        GroundedMinutesResult result = new GroundedMinutesResult(
                "default.md", "1.0.0", List.of(), List.of(), List.of(),
                List.of(action("Send the project update", List.of("seg_001"), "participant-1",
                        List.of("missing-owner-evidence"), "2026-10-16", List.of("missing-date-evidence"))),
                List.of());

        StructuredMinutes normalized = normalizer.normalize(command(TRANSCRIPT), result);

        assertThat(normalized.actionItems()).containsExactly(
                new StructuredMinutes.ActionItem(null, "Send the project update", null));
    }

    @Test
    void keepsValidDateWhenDateEvidenceExistsAndAlwaysLeavesOwnerNullWithoutSpeakerMapping() {
        GroundedMinutesResult result = new GroundedMinutesResult(
                "default.md", "1.0.0", List.of(), List.of(), List.of(),
                List.of(action("Send the project update", List.of("seg_001"), "participant-1",
                        List.of("seg_001"), "2026-10-16", List.of("seg_002"))), List.of());

        List<TranscriptSegment> transcript = List.of(
                TRANSCRIPT.getFirst(),
                new TranscriptSegment("seg_002", "speaker_b", 500, 900, "The due date is 2026-10-16."));
        StructuredMinutes normalized = normalizer.normalize(command(transcript), result);

        assertThat(normalized.actionItems()).containsExactly(
                new StructuredMinutes.ActionItem(null, "Send the project update", LocalDate.of(2026, 10, 16)));
    }

    @Test
    void clearsDateWhenCitedSegmentDoesNotContainTheCanonicalDate() {
        GroundedMinutesResult result = new GroundedMinutesResult(
                "default.md", "1.0.0", List.of(), List.of(), List.of(),
                List.of(action("Send the project update", List.of("seg_001"), null, List.of(),
                        "2026-10-16", List.of("seg_002"))), List.of());

        StructuredMinutes normalized = normalizer.normalize(command(TRANSCRIPT), result);

        assertThat(normalized.actionItems()).containsExactly(
                new StructuredMinutes.ActionItem(null, "Send the project update", null));
    }

    @Test
    void rejectsTemplateMismatchRosterExternalOwnerAndInvalidCalendarDate() {
        GroundedMinutesResult wrongTemplate = new GroundedMinutesResult(
                "project.md", "1.0.0", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> normalizer.normalize(command(TRANSCRIPT), wrongTemplate))
                .isInstanceOf(IllegalArgumentException.class);
        GroundedMinutesResult wrongVersion = new GroundedMinutesResult(
                "default.md", "9.9.9", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> normalizer.normalize(command(TRANSCRIPT), wrongVersion))
                .isInstanceOf(IllegalArgumentException.class);

        GroundedMinutesResult externalOwner = new GroundedMinutesResult(
                "default.md", "1.0.0", List.of(), List.of(), List.of(),
                List.of(action("Do work", List.of("seg_001"), "outside-roster", List.of("seg_001"), null, List.of())),
                List.of());
        assertThatThrownBy(() -> normalizer.normalize(command(TRANSCRIPT), externalOwner))
                .isInstanceOf(IllegalArgumentException.class);

        GroundedMinutesResult invalidDate = new GroundedMinutesResult(
                "default.md", "1.0.0", List.of(), List.of(), List.of(),
                List.of(action("Do work", List.of("seg_001"), null, List.of(), "2026-02-30", List.of("seg_001"))),
                List.of());
        assertThatThrownBy(() -> normalizer.normalize(command(TRANSCRIPT), invalidDate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingClaimEvidenceShapeInsteadOfSilentlyFilteringMalformedOutput() {
        GroundedMinutesResult malformed = new GroundedMinutesResult(
                "default.md", "1.0.0", List.of(new GroundedMinutesResult.TextClaim("Claim", null)),
                List.of(), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> normalizer.normalize(command(TRANSCRIPT), malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MinutesCommand command(List<TranscriptSegment> transcript) {
        return new MinutesCommand("default.md", "1.0.0", "# Minutes template",
                List.of(new MeetingSession.ParticipantReference("participant-1", "Ada Lovelace")),
                Map.of(), transcript);
    }

    private static GroundedMinutesResult.TextClaim claim(String text, String evidence) {
        return new GroundedMinutesResult.TextClaim(text, evidence.isEmpty() ? List.of() : List.of(evidence));
    }

    private static GroundedMinutesResult.ActionItemClaim action(String task, List<String> taskEvidence,
            String owner, List<String> ownerEvidence, String date, List<String> dateEvidence) {
        return new GroundedMinutesResult.ActionItemClaim(
                task, taskEvidence, owner, ownerEvidence, date, dateEvidence);
    }
}
