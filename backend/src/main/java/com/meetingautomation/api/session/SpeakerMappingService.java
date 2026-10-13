package com.meetingautomation.api.session;

import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.api.participant.ParticipantNotFoundException;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantListingProvider;
import com.meetingautomation.document.Speaker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public final class SpeakerMappingService {
    private static final Logger LOG = LoggerFactory.getLogger(SpeakerMappingService.class);
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private final MeetingSessionStore sessionStore;
    private final DocumentProviderResolver providerResolver;

    public SpeakerMappingService(MeetingSessionStore sessionStore, DocumentProviderResolver providerResolver) {
        this.sessionStore = sessionStore;
        this.providerResolver = providerResolver;
    }

    public SpeakerMappingResponse replace(
            String sessionId, String ifMatch, SpeakerMappingRequest request, String requestTraceId) {
        return execute(sessionId, ifMatch, () -> request, requestTraceId);
    }

    public SpeakerMappingResponse replaceJson(
            String sessionId, String ifMatch, JsonNode body, String requestTraceId) {
        return execute(sessionId, ifMatch, () -> SpeakerMappingRequest.parse(body), requestTraceId);
    }

    private SpeakerMappingResponse execute(
            String sessionId, String ifMatch, Supplier<SpeakerMappingRequest> requestSupplier, String requestTraceId) {
        String traceId = requestTraceId != null && SAFE_TRACE_ID.matcher(requestTraceId).matches()
                ? requestTraceId : "tr_" + UUID.randomUUID();
        String loggedSessionId = sessionId != null && SAFE_TRACE_ID.matcher(sessionId).matches()
                ? sessionId : "redacted";
        try {
            SpeakerMappingResponse result = sessionStore.withSessionLock(sessionId,
                    () -> replaceLocked(sessionId, ifMatch, requestSupplier));
            LOG.info("Speaker mapping operation traceId={} sessionId={} stage=SPEAKER_MAPPING outcome=COMMITTED",
                    traceId, loggedSessionId);
            return result;
        } catch (RuntimeException failure) {
            LOG.warn("Speaker mapping operation traceId={} sessionId={} stage=SPEAKER_MAPPING outcome=REJECTED errorCode={}",
                    traceId, loggedSessionId, safeErrorCode(failure));
            throw failure;
        }
    }

    private SpeakerMappingResponse replaceLocked(
            String sessionId, String ifMatch, Supplier<SpeakerMappingRequest> requestSupplier) {
        MeetingSession session = sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
        if (!"REVIEW".equals(session.status())) throw new SessionStateConflictException();
        int expectedVersion = parseVersion(ifMatch);
        SpeakerMappingRequest request = requestSupplier.get();
        if (request == null || request.mappings() == null) throw invalid("mappings");

        List<SpeakerMappingRequest.Mapping> mappings = request.mappings();
        if (mappings.size() != session.speakers().size()) throw invalid("mappings");
        Map<String, SpeakerMappingRequest.Mapping> bySpeaker = new LinkedHashMap<>();
        for (SpeakerMappingRequest.Mapping mapping : mappings) {
            if (mapping == null || mapping.speakerId() == null || mapping.speakerId().isBlank()
                    || bySpeaker.putIfAbsent(mapping.speakerId(), mapping) != null) {
                throw invalid("mappings");
            }
        }
        Set<String> expectedSpeakerIds = new HashSet<>();
        session.speakers().forEach(speaker -> expectedSpeakerIds.add(speaker.speakerId()));
        if (!bySpeaker.keySet().equals(expectedSpeakerIds)) throw invalid("mappings");

        Set<String> selectedParticipantIds = new HashSet<>(session.meeting().participantIds());
        Set<String> requestedParticipantIds = new HashSet<>();
        for (SpeakerMappingRequest.Mapping mapping : mappings) {
            String participantId = mapping.participantId();
            if (participantId != null) {
                if (participantId.isBlank()) throw invalid("mappings");
                if (!selectedParticipantIds.contains(participantId)) throw new ParticipantNotFoundException();
                requestedParticipantIds.add(participantId);
            }
        }

        Map<String, Participant> participants = requestedParticipantIds.isEmpty()
                ? Map.of() : loadRoster(requestedParticipantIds);
        List<Speaker> replacements = new ArrayList<>(session.speakers().size());
        for (Speaker speaker : session.speakers()) {
            replacements.add(speaker.withParticipantId(bySpeaker.get(speaker.speakerId()).participantId()));
        }

        // The store rechecks status before the expected version at the atomic commit point.
        MeetingSession updated = sessionStore.replaceSpeakerMappings(sessionId, expectedVersion, replacements);
        List<SpeakerMappingResponse.Mapping> responseMappings = updated.speakers().stream()
                .map(speaker -> {
                    Participant participant = speaker.participantId() == null
                            ? null : participants.get(speaker.participantId());
                    return new SpeakerMappingResponse.Mapping(speaker.speakerId(), speaker.participantId(),
                            participant == null ? null : participant.name());
                })
                .toList();
        return new SpeakerMappingResponse(new SpeakerMappingResponse.Data(updated.version(), responseMappings));
    }

    private Map<String, Participant> loadRoster(Set<String> requestedParticipantIds) {
        ParticipantListingProvider provider;
        try {
            provider = providerResolver.selectedProvider()
                    .filter(ParticipantListingProvider.class::isInstance)
                    .map(ParticipantListingProvider.class::cast)
                    .orElseThrow(DocumentProviderException::structureNotFound);
            if (!providerResolver.configured()) throw DocumentProviderException.structureNotFound();
        } catch (DocumentProviderException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw DocumentProviderException.participantListFailed(false, failure);
        }

        DocumentStructure structure;
        try {
            structure = provider.discoverParticipantStructure(providerResolver.rootId());
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code())) throw failure;
            throw DocumentProviderException.participantListFailed(failure.retryable(), failure);
        } catch (RuntimeException failure) {
            throw DocumentProviderException.participantListFailed(false, failure);
        }

        List<Participant> roster;
        try {
            roster = provider.listParticipants(structure.participantsPageId());
        } catch (DocumentProviderException failure) {
            if ("DOCUMENT_STRUCTURE_NOT_FOUND".equals(failure.code())) throw failure;
            throw DocumentProviderException.participantListFailed(failure.retryable(), failure);
        } catch (RuntimeException failure) {
            throw DocumentProviderException.participantListFailed(false, failure);
        }
        if (roster == null) throw DocumentProviderException.participantListFailed(false);
        Map<String, Participant> participants = new LinkedHashMap<>();
        for (Participant participant : roster) {
            if (participant == null || participant.id() == null || participant.id().isBlank()
                    || participant.name() == null || participant.name().isBlank()
                    || participants.putIfAbsent(participant.id(), participant) != null) {
                throw DocumentProviderException.participantListFailed(false);
            }
        }
        if (!participants.keySet().containsAll(requestedParticipantIds)) throw new ParticipantNotFoundException();
        return participants;
    }

    private static int parseVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9]+\"")) throw invalid("If-Match");
        try {
            return Integer.parseInt(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException invalidVersion) {
            throw invalid("If-Match");
        }
    }

    private static ApiValidationException invalid(String field) {
        return new ApiValidationException(field);
    }

    private static String safeErrorCode(RuntimeException failure) {
        if (failure instanceof DocumentProviderException providerFailure) return providerFailure.code();
        if (failure instanceof ApiValidationException) return "VALIDATION_FAILED";
        if (failure instanceof AudioSessionNotFoundException) return "SESSION_NOT_FOUND";
        if (failure instanceof SessionStateConflictException) return "SESSION_STATE_CONFLICT";
        if (failure instanceof SessionVersionConflictException) return "SESSION_VERSION_CONFLICT";
        if (failure instanceof ParticipantNotFoundException) return "PARTICIPANT_NOT_FOUND";
        return "INTERNAL_ERROR";
    }
}
