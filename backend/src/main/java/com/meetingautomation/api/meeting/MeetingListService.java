package com.meetingautomation.api.meeting;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.transfer.DocumentTransferPort;
import com.meetingautomation.document.transfer.TransferRecord;
import com.meetingautomation.api.error.ApiValidationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class MeetingListService {
    private static final int DEFAULT_LIMIT = 100;
    private final DocumentProviderResolver providerResolver;

    public MeetingListService(DocumentProviderResolver providerResolver) {
        this.providerResolver = providerResolver;
    }

    public List<MeetingListResponse.Item> list(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > 100) throw new ApiValidationException("limit");
        providerResolver.requireActive();
        DocumentStructureProvider selected = providerResolver.selectedProvider()
                .orElseThrow(DocumentProviderException::structureNotFound);
        if (!(selected instanceof DocumentTransferPort transfer)) {
            throw DocumentProviderException.structureNotFound();
        }

        try {
            List<TransferRecord> records = transfer.listMeetingRecords();
            if (records == null) throw DocumentProviderException.documentFailed(false);
            List<MeetingListResponse.Item> items = new ArrayList<>(records.size());
            for (TransferRecord record : records) items.add(toItem(record));
            items.sort(Comparator.comparing((MeetingListResponse.Item item) -> item.meetingAt().toInstant())
                    .reversed().thenComparing(MeetingListResponse.Item::documentId));
            return List.copyOf(items.subList(0, Math.min(limit, items.size())));
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (RuntimeException unsafeOrIncompleteProviderData) {
            throw DocumentProviderException.documentFailed(false, unsafeOrIncompleteProviderData);
        }
    }

    private static MeetingListResponse.Item toItem(TransferRecord record) {
        if (record == null || record.sourceDocumentId() == null || record.sourceDocumentId().isBlank()
                || record.title() == null || record.title().isBlank() || record.marker() == null
                || record.marker().meetingAt() == null || record.marker().participantIds() == null
                || record.participants() == null) {
            throw DocumentProviderException.documentFailed(false);
        }
        List<String> participantIds = record.marker().participantIds();
        Set<String> uniqueIds = new HashSet<>(participantIds);
        if (uniqueIds.size() != participantIds.size()) throw DocumentProviderException.documentFailed(false);
        Map<String, String> names = new HashMap<>();
        for (ParticipantSummary participant : record.participants()) {
            if (participant == null || participant.id() == null || participant.name() == null
                    || participant.name().isBlank() || names.putIfAbsent(participant.id(), participant.name()) != null) {
                throw DocumentProviderException.documentFailed(false);
            }
        }
        if (!names.keySet().equals(uniqueIds)) throw DocumentProviderException.documentFailed(false);
        List<ParticipantSummary> orderedRoster = new ArrayList<>(participantIds.size());
        for (String id : participantIds) {
            String name = names.get(id);
            if (id == null || id.isBlank() || name == null) throw DocumentProviderException.documentFailed(false);
            orderedRoster.add(new ParticipantSummary(id, name));
        }
        return new MeetingListResponse.Item(record.sourceDocumentId(), record.title(), record.marker().meetingAt(),
                orderedRoster, safeDocumentUrl(record.documentUrl()));
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
