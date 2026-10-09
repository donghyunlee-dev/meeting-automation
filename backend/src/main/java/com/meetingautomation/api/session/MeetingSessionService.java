package com.meetingautomation.api.session;

import com.meetingautomation.api.template.TemplateCatalog;
import com.meetingautomation.api.template.TemplateMetadata;
import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantListingProvider;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class MeetingSessionService {
    private final TemplateCatalog templateCatalog;
    private final DocumentProviderResolver providerResolver;
    private final MeetingSessionStore sessionStore;
    private final MeetingSessionCreationCoordinator coordinator;
    private final UploadPolicy uploadPolicy;

    public MeetingSessionService(
            TemplateCatalog templateCatalog,
            DocumentProviderResolver providerResolver,
            MeetingSessionStore sessionStore,
            MeetingSessionCreationCoordinator coordinator,
            @Value("${RECORDING_CHUNK_DURATION_SECONDS:15}") int chunkDurationSeconds,
            @Value("${MAX_CHUNK_BYTES:5242880}") int maxChunkBytes,
            @Value("${ACCEPTED_AUDIO_MIME_TYPES:audio/webm,audio/mp4}") String acceptedMimeTypes) {
        this.templateCatalog = templateCatalog;
        this.providerResolver = providerResolver;
        this.sessionStore = sessionStore;
        this.coordinator = coordinator;
        this.uploadPolicy = new UploadPolicy(chunkDurationSeconds, maxChunkBytes,
                List.of(acceptedMimeTypes.split(",")).stream().map(String::trim).toList());
    }

    public MeetingSession create(String idempotencyKey, CreateMeetingSessionRequest request) {
        MeetingSessionCreationCoordinator.Command command = normalize(request);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw validationFailure("Idempotency-Key");
        }
        return coordinator.execute(idempotencyKey.trim(), command, () -> createOnce(command));
    }

    public UploadPolicy uploadPolicy() {
        return uploadPolicy;
    }

    private MeetingSession createOnce(MeetingSessionCreationCoordinator.Command command) {
        Set<String> supportedTemplateIds = templateCatalog.listTemplates().data().items().stream()
                .map(TemplateMetadata::id).collect(Collectors.toUnmodifiableSet());
        if (!supportedTemplateIds.contains(command.templateId())) throw validationFailure("templateId");

        providerResolver.validateSelection();
        ParticipantListingProvider provider = providerResolver.selectedProvider()
                .filter(ParticipantListingProvider.class::isInstance)
                .map(ParticipantListingProvider.class::cast)
                .orElseThrow(() -> new IllegalStateException("Participant provider is unavailable."));
        if (!providerResolver.configured()) {
            throw new IllegalStateException("Participant provider is not configured.");
        }

        DocumentStructure structure;
        try {
            structure = provider.discoverParticipantStructure(providerResolver.rootId());
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code())) throw failure;
            throw DocumentProviderException.participantListFailed(failure.retryable(), failure);
        } catch (RuntimeException providerFailure) {
            throw DocumentProviderException.participantListFailed(false, providerFailure);
        }
        List<Participant> participants;
        try {
            participants = List.copyOf(provider.listParticipants(structure.participantsPageId()));
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code())) throw failure;
            throw DocumentProviderException.participantListFailed(failure.retryable(), failure);
        } catch (RuntimeException providerFailure) {
            throw DocumentProviderException.participantListFailed(false, providerFailure);
        }
        Set<String> availableIds = participants.stream().map(Participant::id).collect(Collectors.toSet());
        if (!availableIds.containsAll(command.participantIds())) throw validationFailure("participantIds");

        return sessionStore.save(MeetingSessionCreationCoordinator.newSession(command));
    }

    private static MeetingSessionCreationCoordinator.Command normalize(CreateMeetingSessionRequest request) {
        if (request == null) throw validationFailure("request");
        String title = request.title() == null ? null : request.title().trim();
        String templateId = request.templateId() == null ? null : request.templateId().trim();
        String timezone = request.timezone() == null ? null : request.timezone().trim();
        String recoveryKey = request.recoveryKey() == null ? null : request.recoveryKey().trim();
        List<String> participantIds = request.participantIds() == null ? List.of()
                : request.participantIds().stream().map(id -> id == null ? null : id.trim()).toList();

        if (title == null || title.isBlank()) throw validationFailure("title");
        if (templateId == null || templateId.isBlank()) throw validationFailure("templateId");
        if (participantIds.isEmpty() || participantIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw validationFailure("participantIds");
        }
        if (participantIds.stream().distinct().count() != participantIds.size()) {
            throw validationFailure("participantIds");
        }
        if (timezone == null || !isIanaTimezone(timezone)) throw validationFailure("timezone");
        if (recoveryKey == null || recoveryKey.isBlank()) throw validationFailure("recoveryKey");
        return new MeetingSessionCreationCoordinator.Command(title, templateId, participantIds, timezone, recoveryKey);
    }

    private static boolean isIanaTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
            return ZoneId.getAvailableZoneIds().contains(timezone);
        } catch (DateTimeException invalidZone) {
            return false;
        }
    }

    private static ApiValidationException validationFailure(String field) {
        return new ApiValidationException(field);
    }
}
