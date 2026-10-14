package com.meetingautomation.api.session;

import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import com.meetingautomation.api.error.ApiValidationException;
import com.meetingautomation.api.idempotency.IdempotencyKeyConflictException;
import com.meetingautomation.api.template.TemplateCatalog;
import com.meetingautomation.api.template.TemplateDefinition;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public final class MinutesRegenerationService {
    private final MeetingSessionStore sessionStore;
    private final TemplateCatalog templateCatalog;
    private final MinutesRegenerationQueue queue;
    private final Map<String, AcceptedRequest> acceptedRequests = new HashMap<>();

    public MinutesRegenerationService(MeetingSessionStore sessionStore, TemplateCatalog templateCatalog,
            MinutesRegenerationQueue queue) {
        this.sessionStore = sessionStore;
        this.templateCatalog = templateCatalog;
        this.queue = queue;
    }

    public synchronized MinutesRegenerationResponse regenerate(
            String sessionId, String ifMatch, String idempotencyKey, JsonNode body) {
        MinutesRegenerationRequest request = MinutesRegenerationRequest.parse(body);
        int expectedVersion = parseVersion(ifMatch);
        String key = normalizeKey(idempotencyKey);
        RequestFingerprint fingerprint = new RequestFingerprint(sessionId, request.templateId(), expectedVersion);

        return sessionStore.withSessionLock(sessionId, () -> {
            AcceptedRequest prior = acceptedRequests.get(key);
            if (prior != null) {
                if (!prior.fingerprint().equals(fingerprint)) throw new IdempotencyKeyConflictException();
                return prior.response();
            }
            MeetingSession current = sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
            if (!"REVIEW".equals(current.status())) throw new SessionStateConflictException();
            if (current.version() != expectedVersion) throw new SessionVersionConflictException();

            TemplateDefinition template = loadSelectedTemplate(request.templateId());
            String operationId = UUID.randomUUID().toString();
            sessionStore.startMinutesRegeneration(sessionId, expectedVersion, operationId, template.id());
            MinutesRegenerationResponse response = MinutesRegenerationResponse.accepted(sessionId, template.id());
            try {
                queue.enqueue(new MinutesRegenerationJob(operationId, sessionId, template));
            } catch (RuntimeException queueFailure) {
                sessionStore.failMinutesRegeneration(sessionId, operationId);
                throw queueFailure;
            }
            acceptedRequests.put(key, new AcceptedRequest(fingerprint, response));
            return response;
        });
    }

    private TemplateDefinition loadSelectedTemplate(String templateId) {
        boolean supported = templateCatalog.listTemplates().data().items().stream()
                .anyMatch(template -> template.id().equals(templateId));
        if (!supported) throw new ApiValidationException("templateId");
        try {
            return templateCatalog.loadTemplate(templateId);
        } catch (IllegalArgumentException unsupported) {
            throw new ApiValidationException("templateId");
        }
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.trim().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiValidationException("Idempotency-Key");
        }
        return idempotencyKey.trim();
    }

    private static int parseVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9]+\"")) throw new ApiValidationException("If-Match");
        try {
            return Integer.parseInt(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException invalidVersion) {
            throw new ApiValidationException("If-Match");
        }
    }

    private record RequestFingerprint(String sessionId, String templateId, int expectedVersion) { }
    private record AcceptedRequest(RequestFingerprint fingerprint, MinutesRegenerationResponse response) { }
}

