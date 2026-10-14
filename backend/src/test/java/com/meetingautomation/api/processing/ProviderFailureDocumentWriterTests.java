package com.meetingautomation.api.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.ProcessingFailure;
import com.meetingautomation.document.CreateMeetingCommand;
import com.meetingautomation.document.DocumentProvider;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProviderFailureDocumentWriterTests {
    @Test
    void writesOnlyAllowlistedFailureMetadataAndOmitsExistingMinutesAndTranscript() {
        DocumentProviderResolver resolver = mock(DocumentProviderResolver.class);
        DocumentProvider provider = mock(DocumentProvider.class);
        when(resolver.selectedProvider()).thenReturn(Optional.of(provider));
        when(provider.findMeetingBySessionId("session-safe-id")).thenReturn(Optional.empty());
        when(provider.createMeeting(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new MeetingDocumentRef("document-safe-id", "https://docs.example/safe"));
        ProviderFailureDocumentWriter writer = new ProviderFailureDocumentWriter(resolver);
        var existingMinutes = new StructuredMinutes("template", "1", "transcript-private-canary",
                List.of("discussion-private-canary"), List.of("decision-private-canary"),
                List.of(new StructuredMinutes.ActionItem("participant-1", "task-private-canary", null)),
                List.of("followup-private-canary"));
        var session = new MeetingSession("session-safe-id", 3, "PROCESSING_FAILED", 1,
                new MeetingSession.Meeting("Synthetic meeting", "template", "1", List.of("participant-1"),
                        "UTC", List.of(new MeetingSession.ParticipantReference("participant-1", "Synthetic user"))),
                "TRANSCRIPTION", 100, List.of(),
                List.of(new TranscriptSegment("segment-1", "participant-1", 0, 1000,
                        "transcript-private-canary")),
                new ProcessingFailure("TRANSCRIPTION", "TRANSCRIPTION_FAILED", false), existingMinutes,
                Instant.parse("2026-10-14T00:00:00Z"));

        MeetingDocumentRef result = writer.write(session, "DISCARDED");

        assertThat(result).isEqualTo(new MeetingDocumentRef("document-safe-id", "https://docs.example/safe"));
        ArgumentCaptor<CreateMeetingCommand> command = ArgumentCaptor.forClass(CreateMeetingCommand.class);
        verify(provider).findMeetingBySessionId("session-safe-id");
        verify(provider).createMeeting(command.capture());
        assertThat(command.getValue().title()).isEqualTo("Synthetic meeting");
        assertThat(command.getValue().participants()).containsExactly(
                new com.meetingautomation.document.ParticipantSummary("participant-1", "Synthetic user"));
        assertThat(command.getValue().minutes().summary()).isEqualTo(
                "Recording processing failed at TRANSCRIPTION (TRANSCRIPTION_FAILED). Audio disposition: DISCARDED.");
        assertThat(command.getValue().minutes().discussionPoints()).isEmpty();
        assertThat(command.getValue().minutes().decisions()).isEmpty();
        assertThat(command.getValue().minutes().actionItems()).isEmpty();
        assertThat(command.getValue().minutes().followUps()).isEmpty();
        assertThat(command.getValue().transcript()).isEmpty();
        verifyNoMoreInteractions(provider);
    }
}
