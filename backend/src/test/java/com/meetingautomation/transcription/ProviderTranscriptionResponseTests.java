package com.meetingautomation.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderTranscriptionResponseTests {
    @Test
    void preservesProviderLabelsAndSegmentOrderWithoutCreatingInternalSpeakerIds() {
        List<ProviderTranscriptionResponse.Speaker> speakers = new ArrayList<>(List.of(
                new ProviderTranscriptionResponse.Speaker("speaker_z"),
                new ProviderTranscriptionResponse.Speaker("speaker_a")));
        List<ProviderTranscriptionResponse.Segment> segments = new ArrayList<>(List.of(
                new ProviderTranscriptionResponse.Segment("seg_8", "speaker_z", 800, 900, " first "),
                new ProviderTranscriptionResponse.Segment(null, "speaker_a", 100, 200, "second")));

        ProviderTranscriptionResponse response = new ProviderTranscriptionResponse(speakers, segments);
        speakers.clear();
        segments.clear();

        assertEquals(List.of("speaker_z", "speaker_a"),
                response.speakers().stream().map(ProviderTranscriptionResponse.Speaker::providerLabel).toList());
        assertEquals("seg_8", response.segments().getFirst().providerSegmentId());
        assertEquals(" first ", response.segments().getFirst().text());
        assertEquals(800, response.segments().getFirst().startMs());
        assertEquals("speaker_a", response.segments().get(1).providerLabel());
        assertFalse(response.toString().contains("speaker_z"));
        assertFalse(response.segments().getFirst().toString().contains("first"));
        response.validateMeetingDuration(1000);
    }

    @Test
    void acceptsEmptyNoSpeechResult() {
        ProviderTranscriptionResponse response = new ProviderTranscriptionResponse(List.of(), List.of());

        assertEquals(List.of(), response.speakers());
        assertEquals(List.of(), response.segments());
    }

    @Test
    void rejectsUnknownSpeakerReferencesAndInvalidSegmentContent() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderTranscriptionResponse(
                List.of(new ProviderTranscriptionResponse.Speaker("speaker_1")),
                List.of(new ProviderTranscriptionResponse.Segment(null, "speaker_2", 0, 1, "spoken"))));
        assertThrows(IllegalArgumentException.class, () -> new ProviderTranscriptionResponse(
                List.of(new ProviderTranscriptionResponse.Speaker("speaker_1")),
                List.of(new ProviderTranscriptionResponse.Segment(null, "speaker_1", 2, 1, "spoken"))));
        assertThrows(IllegalArgumentException.class, () -> new ProviderTranscriptionResponse(
                List.of(new ProviderTranscriptionResponse.Speaker("speaker_1")),
                List.of(new ProviderTranscriptionResponse.Segment(null, "speaker_1", 0, 1, " \t "))));
    }

    @Test
    void rejectsSegmentBeyondMeetingDurationWithoutChangingItsLabel() {
        ProviderTranscriptionResponse response = new ProviderTranscriptionResponse(
                List.of(new ProviderTranscriptionResponse.Speaker("provider-label")),
                List.of(new ProviderTranscriptionResponse.Segment("provider-id", "provider-label", 0, 1001, "spoken")));

        assertThrows(IllegalArgumentException.class, () -> response.validateMeetingDuration(1000));
    }
}
