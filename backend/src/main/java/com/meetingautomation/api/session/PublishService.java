package com.meetingautomation.api.session;

import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import com.meetingautomation.api.error.FailureContext;
import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.DocumentProviderResolver;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
final class PublishService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final MeetingSessionStore sessions;
    private final DocumentProviderResolver resolver;
    private final DocumentConnectionGate gate;
    private final PublishQueue queue;

    PublishService(MeetingSessionStore sessions, DocumentProviderResolver resolver,
            DocumentConnectionGate gate, PublishQueue queue) {
        this.sessions = sessions; this.resolver = resolver; this.gate = gate; this.queue = queue;
    }

    PublishResponse publish(String sessionId, String ifMatch, String keyHeader, JsonNode body) {
        return publish(sessionId, ifMatch, keyHeader, body, null);
    }

    PublishResponse publish(String sessionId, String ifMatch, String keyHeader, JsonNode body, String traceId) {
        PublishRequest request = PublishRequest.parse(body);
        int expected = parseVersion(ifMatch);
        String key = normalizeKey(keyHeader);
        MeetingSessionStore.PublishFingerprint fingerprint = new MeetingSessionStore.PublishFingerprint(
                sessionId, expected, request.emailRecipientParticipantIds(), request.notificationEnabled());
        return sessions.withSessionLock(sessionId, () -> {
            PublishResponse replay = sessions.replayPublish(key, fingerprint);
            if (replay != null) return replay;
            MeetingSession current = sessions.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
            if (!("CONFIRMED".equals(current.status()) || "DOCUMENT_FAILED".equals(current.status())))
                throw new SessionStateConflictException();
            if (current.version() != expected) throw new SessionVersionConflictException();
            if (!current.meeting().participantIds().containsAll(request.emailRecipientParticipantIds()))
                throw new ApiValidationException("emailRecipientParticipantIds");
            GlobalDocumentSettings.Connection connection = resolver.active()
                    .orElseThrow(() -> new com.meetingautomation.document.settings.SettingsException("DOCUMENT_SETUP_REQUIRED", 409));
            DocumentConnectionGate.WriteLease lease = gate.beginWrite(current.documentConnectionVersion());
            boolean committed = false;
            try {
                MeetingSessionStore.PublishAcceptance accepted = sessions.acceptPublish(sessionId, expected, key,
                        fingerprint, request.emailRecipientParticipantIds(), request.notificationEnabled());
                committed = accepted.newlyAccepted();
                queue.enqueue(new PublishJob(UUID.randomUUID().toString(), accepted.session(), connection, lease,
                        FailureContext.correlationId(traceId)));
                return accepted.response();
            } catch (RuntimeException rejected) {
                lease.close();
                if (committed) sessions.rollbackPublish(sessionId, key);
                throw rejected;
            }
        });
    }

    private static String normalizeKey(String key) {
        if (key == null || !KEY.matcher(key.trim()).matches()) throw new ApiValidationException("Idempotency-Key");
        return key.trim();
    }
    private static int parseVersion(String value) {
        if (value == null || !value.matches("\"[0-9]+\"")) throw new ApiValidationException("If-Match");
        try { return Integer.parseInt(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException failure) { throw new ApiValidationException("If-Match"); }
    }
}
