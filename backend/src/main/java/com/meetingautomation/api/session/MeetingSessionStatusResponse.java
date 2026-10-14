package com.meetingautomation.api.session;

import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record MeetingSessionStatusResponse(Data data) {
    private static final List<String> REVIEW_ACTIONS = List.of(
            "UPDATE_SPEAKER_MAPPING", "UPDATE_MINUTES", "REGENERATE_MINUTES", "CONFIRM");
    private static final List<String> PUBLISH_ACTIONS = List.of("PUBLISH");

    public record Data(
            String sessionId,
            int version,
            String status,
            Meeting meeting,
            Processing processing,
            List<SpeakerView> speakers,
            List<TranscriptView> transcript,
            StructuredMinutes minutes,
            List<String> allowedActions,
            @JsonInclude(JsonInclude.Include.NON_NULL) LastOperation lastOperation,
            @JsonInclude(JsonInclude.Include.NON_NULL) Document document,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Delivery> deliveries) {
        public Data {
            speakers = List.copyOf(speakers);
            transcript = List.copyOf(transcript);
            allowedActions = List.copyOf(allowedActions);
            if (deliveries != null) deliveries = List.copyOf(deliveries);
        }
    }

    public record LastOperation(String type, String outcome, String requestedTemplateId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String errorCode) { }

    public record Document(String documentId, String documentUrl) { }

    public record Delivery(String deliveryId, String channel, String recipientParticipantId, String status,
            int attemptCount, boolean retryable,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant lastAttemptAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String errorCode) { }

    public record Meeting(List<String> participantIds) {
        public Meeting {
            participantIds = List.copyOf(participantIds);
        }
    }

    public record Processing(
            String stage,
            int progressPercent,
            String errorCode,
            Boolean retryable,
            Boolean audioAvailable,
            Instant audioExpiresAt) {
    }

    public record SpeakerView(String speakerId, String label, String participantId) {
        @Override
        public String toString() {
            return "SpeakerView[speakerId=" + speakerId + ", label=<redacted>, participantId="
                    + participantId + "]";
        }
    }

    public record TranscriptView(String segmentId, String speakerId, long startMs, long endMs, String text) {
        @Override
        public String toString() {
            return "TranscriptView[segmentId=" + segmentId + ", speakerId=" + speakerId
                    + ", startMs=" + startMs + ", endMs=" + endMs + ", text=<redacted>]";
        }
    }

    static MeetingSessionStatusResponse from(MeetingSession session, LastOperation lastOperation,
            MeetingSessionStore.PublishSnapshot publishSnapshot) {
        boolean failed = "PROCESSING_FAILED".equals(session.status());
        ProcessingFailure failure = failed ? session.processingFailure() : null;
        Processing processing = new Processing(session.processingStage(), session.progressPercent(),
                failure == null ? null : failure.errorCode(),
                failure == null ? null : failure.retryable(),
                failed ? false : null,
                null);
        boolean reviewReady = "REVIEW".equals(session.status());
        List<SpeakerView> speakers = reviewReady
                ? session.speakers().stream()
                        .map(speaker -> new SpeakerView(speaker.speakerId(), speaker.label(), speaker.participantId()))
                        .toList()
                : List.of();
        List<TranscriptView> transcript = reviewReady
                ? session.transcript().stream()
                        .sorted(Comparator.comparingLong(TranscriptSegment::startMs)
                                .thenComparingLong(TranscriptSegment::endMs))
                        .map(segment -> new TranscriptView(segment.segmentId(), segment.speakerId(),
                                segment.startMs(), segment.endMs(), segment.text()))
                        .toList()
                : List.of();
        StructuredMinutes minutes = reviewReady ? session.minutes() : null;
        List<String> allowedActions = reviewReady ? REVIEW_ACTIONS
                : ("CONFIRMED".equals(session.status()) || "DOCUMENT_FAILED".equals(session.status()))
                        ? PUBLISH_ACTIONS : List.of();
        Document document = "DOCUMENT_SAVED".equals(session.status()) && publishSnapshot != null
                && publishSnapshot.document() != null
                        ? new Document(publishSnapshot.document().documentId(), publishSnapshot.document().documentUrl())
                        : null;
        List<Delivery> deliveries = publishSnapshot == null || "DOCUMENT_FAILED".equals(session.status()) ? null
                : publishSnapshot.deliveries().stream()
                .map(delivery -> new Delivery(delivery.deliveryId(), delivery.channel(),
                        delivery.recipientParticipantId(), delivery.status(), delivery.attemptCount(),
                        delivery.retryable(), delivery.lastAttemptAt(), delivery.errorCode()))
                .toList();
        return new MeetingSessionStatusResponse(new Data(session.sessionId(), session.version(), session.status(),
                new Meeting(session.meeting().participantIds()), processing, speakers, transcript, minutes,
                allowedActions, reviewReady ? lastOperation : null, document, deliveries));
    }
}

