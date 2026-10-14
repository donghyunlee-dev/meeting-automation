package com.meetingautomation.api.session;

import com.meetingautomation.api.error.ApiValidationException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public final class ReviewConfirmationService {
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final MeetingSessionStore sessionStore;
    private final ReviewConfirmationValidator validator;

    public ReviewConfirmationService(MeetingSessionStore sessionStore, ReviewConfirmationValidator validator) {
        this.sessionStore = sessionStore;
        this.validator = validator;
    }

    public ReviewConfirmationResponse confirm(
            String sessionId, String ifMatch, String idempotencyKey, JsonNode body) {
        ReviewConfirmationRequest request = ReviewConfirmationRequest.parse(body);
        int expectedVersion = parseVersion(ifMatch);
        String key = normalizeKey(idempotencyKey);
        ReviewConfirmationFingerprint fingerprint =
                new ReviewConfirmationFingerprint(sessionId, request.confirm(), expectedVersion);
        return sessionStore.confirmReview(sessionId, expectedVersion, key, fingerprint, validator);
    }

    private static String normalizeKey(String key) {
        if (key == null || !VALID_KEY.matcher(key.trim()).matches()) {
            throw new ApiValidationException("Idempotency-Key");
        }
        return key.trim();
    }

    private static int parseVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9]+\"")) throw new ApiValidationException("If-Match");
        try {
            return Integer.parseInt(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException invalidVersion) {
            throw new ApiValidationException("If-Match");
        }
    }

    record ReviewConfirmationFingerprint(String sessionId, boolean confirm, int expectedVersion) { }
}
