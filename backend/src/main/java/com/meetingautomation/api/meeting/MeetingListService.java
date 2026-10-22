package com.meetingautomation.api.meeting;

import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.MeetingSummary;
import com.meetingautomation.document.transfer.DocumentTransferPort;
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
            List<MeetingSummary> meetings = transfer.listMeetingSummaries();
            if (meetings == null) throw DocumentProviderException.documentFailed(false);
            List<MeetingListResponse.Item> items = new ArrayList<>(meetings.size());
            for (MeetingSummary meeting : meetings) items.add(toItem(meeting));
            items.sort(Comparator.comparing((MeetingListResponse.Item item) -> item.meetingAt().toInstant())
                    .reversed().thenComparing(MeetingListResponse.Item::documentId));
            return List.copyOf(items.subList(0, Math.min(limit, items.size())));
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (RuntimeException unsafeOrIncompleteProviderData) {
            throw DocumentProviderException.documentFailed(false, unsafeOrIncompleteProviderData);
        }
    }

    private static MeetingListResponse.Item toItem(MeetingSummary meeting) {
        if (meeting == null || meeting.documentId() == null || meeting.documentId().isBlank()
                || meeting.title() == null || meeting.title().isBlank() || meeting.meetingAt() == null
                || meeting.participants() == null) {
            throw DocumentProviderException.documentFailed(false);
        }
        Set<String> participantIds = new HashSet<>();
        List<ParticipantSummary> participants = new ArrayList<>(meeting.participants().size());
        for (ParticipantSummary participant : meeting.participants()) {
            if (participant == null || participant.id() == null || participant.id().isBlank()
                    || participant.name() == null || participant.name().isBlank()
                    || !participantIds.add(participant.id())) {
                throw DocumentProviderException.documentFailed(false);
            }
            participants.add(participant);
        }
        return new MeetingListResponse.Item(meeting.documentId(), meeting.title(), meeting.meetingAt(),
                participants, safeDocumentUrl(meeting.documentUrl()));
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
