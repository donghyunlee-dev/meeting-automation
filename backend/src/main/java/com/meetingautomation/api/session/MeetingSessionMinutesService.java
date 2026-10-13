package com.meetingautomation.api.session;

import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.api.participant.ParticipantNotFoundException;
import com.meetingautomation.document.StructuredMinutes;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public final class MeetingSessionMinutesService {
    private static final Logger LOG = LoggerFactory.getLogger(MeetingSessionMinutesService.class);
    private static final Pattern SAFE_LOG_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private final MeetingSessionStore sessionStore;

    public MeetingSessionMinutesService(MeetingSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public MinutesUpdateResponse replaceJson(
            String sessionId, String ifMatch, JsonNode body, String requestTraceId) {
        return execute(sessionId, ifMatch, () -> MinutesUpdateRequest.parse(body), requestTraceId);
    }

    private MinutesUpdateResponse execute(
            String sessionId, String ifMatch, Supplier<StructuredMinutes> minutesSupplier, String requestTraceId) {
        String traceId = requestTraceId != null && SAFE_LOG_ID.matcher(requestTraceId).matches()
                ? requestTraceId : "tr_" + UUID.randomUUID();
        String loggedSessionId = sessionId != null && SAFE_LOG_ID.matcher(sessionId).matches()
                ? sessionId : "redacted";
        try {
            MinutesUpdateResponse result = sessionStore.withSessionLock(sessionId,
                    () -> replaceLocked(sessionId, ifMatch, minutesSupplier));
            LOG.info("Minutes update traceId={} sessionId={} stage=MINUTES outcome=COMMITTED",
                    traceId, loggedSessionId);
            return result;
        } catch (RuntimeException failure) {
            LOG.warn("Minutes update traceId={} sessionId={} stage=MINUTES outcome=REJECTED errorCode={}",
                    traceId, loggedSessionId, safeErrorCode(failure));
            throw failure;
        }
    }

    private MinutesUpdateResponse replaceLocked(
            String sessionId, String ifMatch, Supplier<StructuredMinutes> minutesSupplier) {
        MeetingSession current = sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
        if (!"REVIEW".equals(current.status())) throw new SessionStateConflictException();
        int expectedVersion = parseVersion(ifMatch);
        StructuredMinutes replacement = minutesSupplier.get();
        if (!java.util.Objects.equals(current.meeting().templateId(), replacement.templateId())
                || !java.util.Objects.equals(current.meeting().templateVersion(), replacement.templateVersion())) {
            throw new ApiValidationException("templateId");
        }
        List<String> selectedParticipantIds = current.meeting().participantIds();
        for (StructuredMinutes.ActionItem item : replacement.actionItems()) {
            if (item.ownerParticipantId() != null && !selectedParticipantIds.contains(item.ownerParticipantId())) {
                throw new ParticipantNotFoundException();
            }
        }

        MeetingSession updated = sessionStore.replaceMinutes(sessionId, expectedVersion, replacement);
        return MinutesUpdateResponse.from(updated);
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
        if (failure instanceof ApiValidationException) return "VALIDATION_FAILED";
        if (failure instanceof AudioSessionNotFoundException) return "SESSION_NOT_FOUND";
        if (failure instanceof SessionStateConflictException) return "SESSION_STATE_CONFLICT";
        if (failure instanceof SessionVersionConflictException) return "SESSION_VERSION_CONFLICT";
        if (failure instanceof ParticipantNotFoundException) return "PARTICIPANT_NOT_FOUND";
        return "INTERNAL_ERROR";
    }
}
