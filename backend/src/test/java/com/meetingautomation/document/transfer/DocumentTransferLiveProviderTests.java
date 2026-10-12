package com.meetingautomation.document.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.document.confluence.ConfluencePageHierarchyAdapter;
import com.meetingautomation.document.notion.NotionPageHierarchyAdapter;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in real-provider QA. Uses only dedicated QA roots and synthetic records. */
class DocumentTransferLiveProviderTests {
    private static final String VALID_DIGEST = "sha256:" + "0".repeat(64);

    @Test
    void transfersSyntheticMeetingAndParticipantAcrossBothProvidersAndReusesCopies() {
        String notionToken = required("NOTION_API_TOKEN");
        String confluenceToken = required("CONFLUENCE_API_TOKEN");
        String confluenceEmail = required("CONFLUENCE_ACCOUNT_EMAIL");
        String notionSourceRoot = required("NOTION_TRANSFER_QA_SOURCE_ROOT_ID");
        String notionTargetRoot = required("NOTION_TRANSFER_QA_TARGET_ROOT_ID");
        String confluenceRoot = required("CONFLUENCE_TRANSFER_QA_ROOT_ID");
        String confluenceBaseUrl = required("CONFLUENCE_BASE_URL");
        assumeTrue(List.of(notionToken, confluenceToken, confluenceEmail, notionSourceRoot,
                notionTargetRoot, confluenceRoot, confluenceBaseUrl).stream().allMatch(v -> !v.isBlank()),
                "Live provider QA requires dedicated roots and credentials in environment variables");

        var notionSource = new NotionPageHierarchyAdapter(RestClient.builder(), notionToken, notionSourceRoot);
        var notionTarget = new NotionPageHierarchyAdapter(RestClient.builder(), notionToken, notionTargetRoot);
        AtomicInteger confluenceFailureStatus = new AtomicInteger();
        AtomicReference<String> confluenceFailureSummary = new AtomicReference<>("");
        RestClient.Builder confluenceBuilder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            var response = execution.execute(request, body);
            if (!response.getStatusCode().isError()) return response;
            confluenceFailureStatus.set(response.getStatusCode().value());
            byte[] responseBody = response.getBody().readAllBytes();
            try {
                var json = JsonMapper.builder().build().readTree(responseBody);
                String summary = json.path("message").asString("");
                if (summary.isBlank()) summary = json.path("errors").toString();
                confluenceFailureSummary.set(summary.substring(0, Math.min(summary.length(), 300))
                        .replace(confluenceToken, "[redacted]").replace(notionToken, "[redacted]"));
            } catch (RuntimeException ignored) {
                confluenceFailureSummary.set("unstructured provider error");
            }
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
                @Override public java.io.InputStream getBody() { return new ByteArrayInputStream(responseBody); }
                @Override public void close() { response.close(); }
            };
        });
        var confluence = new ConfluencePageHierarchyAdapter(confluenceBuilder, confluenceBaseUrl,
                confluenceEmail, confluenceToken, confluenceRoot);

        String runId = UUID.randomUUID().toString();
        String sourceParticipantId = "qa-participant-" + runId;
        String externalSessionId = "qa-session-" + runId;
        String name = "Transfer QA " + runId;
        String email = "transfer-qa+" + runId + "@example.test";
        var codec = new TransferCodec();
        String seedFingerprint = codec.connectionFingerprint("notion", "synthetic-transfer-qa-seed");

        TransferRecord sourceParticipant = participant(sourceParticipantId, name, email, codec);
        Participant seededParticipant = notionSource.importParticipant(sourceParticipant, seedFingerprint);
        TransferRecord sourceMeeting = meeting(sourceParticipantId, name, externalSessionId);
        MeetingDocumentRef seededMeeting = notionSource.importMeeting(sourceMeeting,
                Map.of(sourceParticipantId, seededParticipant.id()), seedFingerprint);
        assertNotNull(seededMeeting.documentId());

        var service = new DocumentTransferService();
        TransferResult forward;
        try {
            forward = service.transfer(notionSource, confluence);
        } catch (RuntimeException failure) {
            throw new AssertionError("Forward live transfer failed; Confluence HTTP status=" + confluenceFailureStatus.get()
                    + "; provider summary=" + confluenceFailureSummary.get(), failure);
        }
        assertEquals(1, forward.participantsCopied());
        assertEquals(1, forward.meetingsCopied());
        assertEquals(0, forward.participantsReused());
        assertEquals(0, forward.meetingsReused());

        TransferResult replay = service.transfer(notionSource, confluence);
        assertEquals(1, replay.participantsReused());
        assertEquals(1, replay.meetingsReused());
        assertEquals(0, replay.participantsCopied());
        assertEquals(0, replay.meetingsCopied());
        assertTargetRecord(confluence, externalSessionId, name, email);

        TransferResult reverse = service.transfer(confluence, notionTarget);
        assertEquals(1, reverse.participantsCopied());
        assertEquals(1, reverse.meetingsCopied());
        assertTargetRecord(notionTarget, externalSessionId, name, email);

        TransferResult reverseReplay = service.transfer(confluence, notionTarget);
        assertEquals(1, reverseReplay.participantsReused());
        assertEquals(1, reverseReplay.meetingsReused());
        assertEquals(0, reverseReplay.participantsCopied());
        assertEquals(0, reverseReplay.meetingsCopied());
    }

    @Test
    void transfersSyntheticMeetingAndParticipantBetweenNotionRootsAndReadsBackOnReplay() {
        String notionToken = required("NOTION_API_TOKEN");
        String notionSourceRoot = required("NOTION_TRANSFER_QA_SOURCE_ROOT_ID");
        String notionTargetRoot = required("NOTION_TRANSFER_QA_TARGET_ROOT_ID");
        assumeTrue(List.of(notionToken, notionSourceRoot, notionTargetRoot).stream().allMatch(v -> !v.isBlank()),
                "Notion-only live QA requires a token and dedicated synthetic source and target roots");

        var notionSource = new NotionPageHierarchyAdapter(RestClient.builder(), notionToken, notionSourceRoot);
        var notionTarget = new NotionPageHierarchyAdapter(RestClient.builder(), notionToken, notionTargetRoot);
        String runId = UUID.randomUUID().toString();
        String sourceParticipantId = "qa-notion-participant-" + runId;
        String externalSessionId = "qa-notion-session-" + runId;
        String name = "Notion Transfer QA " + runId;
        String email = "notion-transfer-qa+" + runId + "@example.test";
        var codec = new TransferCodec();
        String seedFingerprint = codec.connectionFingerprint("notion", "synthetic-notion-transfer-qa-seed-" + runId);

        Participant seededParticipant = notionSource.importParticipant(
                participant(sourceParticipantId, name, email, codec), seedFingerprint);
        MeetingDocumentRef seededMeeting = notionSource.importMeeting(
                meeting(sourceParticipantId, name, externalSessionId),
                Map.of(sourceParticipantId, seededParticipant.id()), seedFingerprint);
        assertNotNull(seededMeeting.documentId());

        var service = new DocumentTransferService();
        TransferResult first = service.transfer(notionSource, notionTarget);

        assertEquals(1, first.meetingsCopied());
        assertTrue(first.participantsCopied() >= 1);
        assertTargetRecord(notionTarget, externalSessionId, name, email);

        TransferResult replay = service.transfer(notionSource, notionTarget);

        assertEquals(0, replay.participantsCopied());
        assertEquals(0, replay.meetingsCopied());
        assertTrue(replay.participantsReused() >= 1);
        assertEquals(1, replay.meetingsReused());
        assertTargetRecord(notionTarget, externalSessionId, name, email);
    }

    private static void assertTargetRecord(DocumentTransferPort target, String sessionId, String name, String email) {
        TransferManifest manifest = target.readManifest();
        TransferRecord participant = manifest.participants().stream()
                .filter(r -> email.equals(r.participant().email())).findFirst().orElseThrow();
        assertEquals(name, participant.participant().name());
        TransferManifest.Entry meetingEntry = manifest.meetings().stream()
                .filter(e -> sessionId.equals(e.externalSessionId())).findFirst().orElseThrow();
        TransferRecord meeting = target.exportMeeting(meetingEntry.sourceDocumentId());
        assertEquals(name, meeting.title());
        assertEquals("qa-minutes-template", meeting.minutes().templateId());
        assertEquals("A synthetic discussion summary", meeting.minutes().summary());
        assertEquals("qa-transcript-text", meeting.transcript().getFirst().text());
        assertTrue(meeting.participants().stream().anyMatch(p -> participant.participant().id().equals(p.id())));
    }

    private static TransferRecord participant(String id, String name, String email, TransferCodec codec) {
        var marker = new TransferMarker("meeting-automation", "participant", "1.0", null, null, null,
                List.of(), List.of(), null, VALID_DIGEST, null);
        var value = new TransferRecord(id, "synthetic-source-revision", null, marker, name, null, null,
                List.of(), new Participant(id, name, email));
        marker = new TransferMarker(marker.format(), marker.recordType(), marker.schemaVersion(), null, null,
                null, List.of(), List.of(), null, codec.canonicalDigest(value), null);
        return new TransferRecord(id, value.sourceRevision(), null, marker, name, null, null,
                List.of(), value.participant());
    }

    private static TransferRecord meeting(String participantId, String name, String sessionId) {
        var marker = new TransferMarker("meeting-automation", "meeting", "1.0", sessionId,
                OffsetDateTime.parse("2026-10-12T09:00:00+09:00"), "qa-meeting-template",
                List.of(participantId), List.of(new TransferMarker.Speaker("speaker-qa", "QA Speaker", participantId)),
                null, null, null);
        StructuredMinutes minutes = new StructuredMinutes("qa-minutes-template", "1", "A synthetic discussion summary",
                List.of("A synthetic discussion point"), List.of("A synthetic decision"),
                List.of(new StructuredMinutes.ActionItem(participantId, "A synthetic action", LocalDate.parse("2026-10-19"))),
                List.of("A synthetic follow-up"));
        return new TransferRecord("qa-meeting-" + sessionId, "synthetic-source-revision", null, marker, name,
                minutes, List.of(new TranscriptSegment("segment-qa", "speaker-qa", 0, 1200, "qa-transcript-text")),
                List.of(new ParticipantSummary(participantId, name)), null);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
