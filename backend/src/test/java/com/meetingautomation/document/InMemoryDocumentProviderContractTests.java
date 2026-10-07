package com.meetingautomation.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

class InMemoryDocumentProviderContractTests extends DocumentProviderContractTest {
    @Override
    protected Supplier<DocumentProvider> providerFactory() {
        return () -> new InMemoryDocumentProvider(false, false);
    }

    @Override
    protected Supplier<DocumentProvider> failingProviderFactory() {
        return () -> new InMemoryDocumentProvider(true, true);
    }

    private static final class InMemoryDocumentProvider implements DocumentProvider {
        private final boolean failParticipantListing;
        private final boolean failGenericOperations;

        private InMemoryDocumentProvider(boolean failParticipantListing, boolean failGenericOperations) {
            this.failParticipantListing = failParticipantListing;
            this.failGenericOperations = failGenericOperations;
        }

        @Override public ProviderHealth validateConnection() { return new ProviderHealth(true, true, true); }

        @Override
        public DocumentStructure discoverStructure(String rootId) {
            if ("missing-root-child".equals(rootId)) throw DocumentProviderException.structureNotFound();
            return new DocumentStructure(rootId, "meetings-child", "participants-child");
        }

        @Override
        public List<MeetingSummary> listMeetings(int max) {
            failGenericOperationIfConfigured();
            return java.util.stream.IntStream.range(0, max)
                    .mapToObj(index -> summary())
                    .toList();
        }

        @Override
        public MeetingDocument getMeeting(String documentId) {
            if ("meeting-not-found".equals(documentId)) {
                throw DocumentProviderException.meetingNotFound();
            }
            if ("provider-failure".equals(documentId)) {
                throw DocumentProviderException.documentFailed(true,
                        new IllegalStateException("provider-secret-marker raw-response-marker"));
            }
            return document();
        }

        @Override
        public Optional<MeetingDocumentRef> findMeetingBySessionId(String sessionId) {
            failGenericOperationIfConfigured();
            return Optional.of(new MeetingDocumentRef("doc_meeting-1", "https://documents.example/meeting-1"));
        }

        @Override
        public MeetingDocumentRef createMeeting(CreateMeetingCommand command) {
            failGenericOperationIfConfigured();
            return new MeetingDocumentRef("doc_created", "https://documents.example/created");
        }

        @Override
        public List<Participant> listParticipants() {
            if (failParticipantListing) {
                throw DocumentProviderException.participantListFailed(true,
                        new IllegalStateException("provider-secret-marker raw-response-marker"));
            }
            return List.of(new Participant("pt_1", "A User", "a@example.test"));
        }

        @Override
        public Participant createParticipant(CreateParticipantCommand command) {
            failGenericOperationIfConfigured();
            return new Participant("pt_new", command.name(), command.email());
        }

        @Override
        public Participant updateParticipant(String participantId, UpdateParticipantCommand command) {
            failGenericOperationIfConfigured();
            return new Participant(participantId, command.name(), "a@example.test");
        }

        private void failGenericOperationIfConfigured() {
            if (failGenericOperations) {
                throw DocumentProviderException.documentFailed(true,
                        new IllegalStateException("provider-secret-marker raw-response-marker"));
            }
        }

        private static MeetingSummary summary() {
            return new MeetingSummary("doc_meeting-1", "Weekly meeting", at(),
                    List.of(new ParticipantSummary("pt_1", "A User")), "https://documents.example/meeting-1");
        }

        private static MeetingDocument document() {
            CreateMeetingCommand command = meetingCommand();
            return new MeetingDocument("doc_meeting-1", command.title(), command.meetingAt(), command.participants(),
                    command.minutes(), command.transcript(), "https://documents.example/meeting-1");
        }

        private static OffsetDateTime at() { return OffsetDateTime.parse("2026-10-07T10:00:00+09:00"); }
    }
}
