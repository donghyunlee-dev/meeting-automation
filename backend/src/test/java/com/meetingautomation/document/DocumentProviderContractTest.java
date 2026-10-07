package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Reusable provider contract. Adapters can extend this class and provide a test factory. */
public abstract class DocumentProviderContractTest {
    protected abstract Supplier<DocumentProvider> providerFactory();
    protected abstract Supplier<DocumentProvider> failingProviderFactory();

    @Test
    void healthAndMeetingListUseStandardFieldsAndDefaultLimit() {
        DocumentProvider provider = providerFactory().get();
        assertEquals(new ProviderHealth(true, true, true), provider.validateConnection());
        List<MeetingSummary> meetings = provider.listMeetings();
        assertEquals(100, meetings.size());
        MeetingSummary summary = meetings.getFirst();
        assertEquals("doc_meeting-1", summary.documentId());
        assertEquals("Weekly meeting", summary.title());
        assertEquals("pt_1", summary.participants().getFirst().id());
        assertEquals("https://documents.example/meeting-1", summary.documentUrl());
        assertEquals(1, provider.listMeetings(1).size());
    }

    @Test
    void discoversOnlyConfiguredRootAndItsRequiredChildren() {
        DocumentStructure structure = providerFactory().get().discoverStructure("root-configured");

        assertEquals("root-configured", structure.rootId());
        assertEquals("meetings-child", structure.meetingsPageId());
        assertEquals("participants-child", structure.participantsPageId());
    }

    @Test
    void structureAndProviderFailuresUseNormalizedSafeErrors() {
        DocumentProvider provider = failingProviderFactory().get();

        DocumentProviderException structureFailure = assertThrows(
                DocumentProviderException.class, () -> provider.discoverStructure("missing-root-child"));
        assertEquals("DOCUMENT_STRUCTURE_NOT_FOUND", structureFailure.code());
        assertEquals(422, structureFailure.statusCode());
        assertFalse(structureFailure.retryable());

        DocumentProviderException participantFailure = assertThrows(
                DocumentProviderException.class, () -> provider.listParticipants());
        assertEquals("PARTICIPANT_LIST_FAILED", participantFailure.code());
        assertEquals(502, participantFailure.statusCode());
        assertEquals("DOCUMENT_FAILURE", participantFailure.category());
        assertFalse(participantFailure.getMessage().contains("provider-secret-marker"));
        assertFalse(participantFailure.getMessage().contains("raw-response-marker"));
        assertNull(participantFailure.getCause());

        DocumentProviderException documentFailure = assertThrows(
                DocumentProviderException.class, () -> provider.getMeeting("provider-failure"));
        assertEquals("DOCUMENT_FAILED", documentFailure.code());
        assertEquals(502, documentFailure.statusCode());
        assertEquals("DOCUMENT_FAILURE", documentFailure.category());
        assertFalse(documentFailure.getMessage().contains("provider-secret-marker"));
    }

    @Test
    void meetingReadAndCreateReturnOnlyStandardModels() {
        DocumentProvider provider = providerFactory().get();
        MeetingDocument document = provider.getMeeting("doc_meeting-1");
        assertEquals("doc_meeting-1", document.documentId());
        assertEquals("Weekly meeting", document.title());
        assertEquals("pt_1", document.participants().getFirst().id());
        assertEquals("https://documents.example/meeting-1", document.documentUrl());
        assertNotNull(document.minutes());
        assertEquals("seg_1", document.transcript().getFirst().segmentId());

        assertEquals(document.documentId(), provider.findMeetingBySessionId("session-1").orElseThrow().documentId());

        MeetingDocumentRef ref = provider.createMeeting(meetingCommand());
        assertEquals("doc_created", ref.documentId());
        assertEquals("https://documents.example/created", ref.documentUrl());
        assertFalse(ref.toString().contains("raw-response-marker"));
    }

    @Test
    void participantCommandsUseNeutralRosterModel() {
        DocumentProvider provider = providerFactory().get();
        assertEquals(List.of(new Participant("pt_1", "A User", "a@example.test")), provider.listParticipants());
        assertEquals(new Participant("pt_new", "A User", "a@example.test"),
                provider.createParticipant(new CreateParticipantCommand("A User", "a@example.test")));
        assertEquals(new Participant("pt_1", "Updated", "a@example.test"),
                provider.updateParticipant("pt_1", new UpdateParticipantCommand("Updated", null)));
    }

    protected static CreateMeetingCommand meetingCommand() {
        OffsetDateTime meetingAt = OffsetDateTime.parse("2026-10-07T10:00:00+09:00");
        StructuredMinutes minutes = new StructuredMinutes(
                "default.md", "1.0.0", "Summary", List.of("Point"), List.of("Decision"),
                List.of(new StructuredMinutes.ActionItem("pt_1", "Follow up", null)), List.of("Next"));
        return new CreateMeetingCommand(
                "Weekly meeting", meetingAt, List.of(new ParticipantSummary("pt_1", "A User")), minutes,
                List.of(new TranscriptSegment("seg_1", "speaker_a", 0, 1000, "Hello")),
                new MeetingMetadata("1", "session-1", meetingAt, "default.md", List.of("pt_1")));
    }
}
