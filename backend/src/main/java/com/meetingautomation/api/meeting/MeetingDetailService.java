package com.meetingautomation.api.meeting;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.document.transfer.DocumentTransferPort;
import com.meetingautomation.document.transfer.TransferRecord;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class MeetingDetailService {
    private final DocumentProviderResolver providerResolver;

    public MeetingDetailService(DocumentProviderResolver providerResolver) {
        this.providerResolver = providerResolver;
    }

    public MeetingDetailResponse.Data get(String documentId) {
        if (documentId == null || documentId.isBlank()) throw DocumentProviderException.meetingNotFound();
        providerResolver.requireActive();
        DocumentStructureProvider selected = providerResolver.selectedProvider()
                .orElseThrow(DocumentProviderException::structureNotFound);
        if (!(selected instanceof DocumentTransferPort transfer)) {
            throw DocumentProviderException.structureNotFound();
        }

        try {
            TransferRecord record = transfer.exportMeeting(documentId);
            if (record == null) throw DocumentProviderException.meetingNotFound();
            return toData(documentId, record);
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (RuntimeException incompleteOrUnsafeProviderData) {
            throw DocumentProviderException.documentFailed(false, incompleteOrUnsafeProviderData);
        }
    }

    private static MeetingDetailResponse.Data toData(String requestedId, TransferRecord record) {
        if (record.sourceDocumentId() == null || !requestedId.equals(record.sourceDocumentId())
                || record.sourceDocumentId().isBlank() || record.title() == null || record.title().isBlank()
                || record.marker() == null || record.marker().meetingAt() == null
                || record.marker().participantIds() == null || record.participants() == null
                || record.minutes() == null || record.transcript() == null) {
            throw DocumentProviderException.documentFailed(false);
        }
        List<ParticipantSummary> participants = orderedParticipants(record);
        validateMinutes(record.minutes(), participants);
        validateTranscript(record.transcript());
        return new MeetingDetailResponse.Data(record.sourceDocumentId(), record.title(), record.marker().meetingAt(),
                participants, record.minutes(), List.copyOf(record.transcript()), safeDocumentUrl(record.documentUrl()));
    }

    private static List<ParticipantSummary> orderedParticipants(TransferRecord record) {
        List<String> ids = record.marker().participantIds();
        Set<String> uniqueIds = new HashSet<>(ids);
        if (uniqueIds.size() != ids.size()) throw DocumentProviderException.documentFailed(false);
        Map<String, String> names = new HashMap<>();
        for (ParticipantSummary participant : record.participants()) {
            if (participant == null || participant.id() == null || participant.id().isBlank()
                    || participant.name() == null || participant.name().isBlank()
                    || names.putIfAbsent(participant.id(), participant.name()) != null) {
                throw DocumentProviderException.documentFailed(false);
            }
        }
        if (!names.keySet().equals(uniqueIds)) throw DocumentProviderException.documentFailed(false);
        List<ParticipantSummary> ordered = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null || id.isBlank()) throw DocumentProviderException.documentFailed(false);
            ordered.add(new ParticipantSummary(id, names.get(id)));
        }
        return List.copyOf(ordered);
    }

    private static void validateMinutes(StructuredMinutes minutes, List<ParticipantSummary> participants) {
        if (minutes.templateId() == null || minutes.templateId().isBlank()
                || minutes.templateVersion() == null || minutes.templateVersion().isBlank()
                || minutes.summary() == null || minutes.discussionPoints() == null || minutes.decisions() == null
                || minutes.actionItems() == null || minutes.followUps() == null) {
            throw DocumentProviderException.documentFailed(false);
        }
        Set<String> participantIds = participants.stream().map(ParticipantSummary::id)
                .collect(java.util.stream.Collectors.toSet());
        if (minutes.discussionPoints().stream().anyMatch(MeetingDetailService::isBlank)
                || minutes.decisions().stream().anyMatch(MeetingDetailService::isBlank)
                || minutes.followUps().stream().anyMatch(MeetingDetailService::isBlank)
                || minutes.actionItems().stream().anyMatch(item -> item == null || isBlank(item.task())
                || item.ownerParticipantId() != null && !participantIds.contains(item.ownerParticipantId()))) {
            throw DocumentProviderException.documentFailed(false);
        }
    }

    private static void validateTranscript(List<TranscriptSegment> transcript) {
        Set<String> ids = new HashSet<>();
        long previousStart = -1;
        for (TranscriptSegment segment : transcript) {
            if (segment == null || isBlank(segment.segmentId()) || isBlank(segment.speakerId())
                    || segment.startMs() < 0 || segment.endMs() < segment.startMs() || segment.text() == null
                    || !ids.add(segment.segmentId()) || segment.startMs() < previousStart) {
                throw DocumentProviderException.documentFailed(false);
            }
            previousStart = segment.startMs();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeDocumentUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
