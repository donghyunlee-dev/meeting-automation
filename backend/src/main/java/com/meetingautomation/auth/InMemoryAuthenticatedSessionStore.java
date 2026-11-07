package com.meetingautomation.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class InMemoryAuthenticatedSessionStore implements AuthenticatedSessionStore {
    private static final Duration IDLE_LIMIT = Duration.ofHours(2);
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Override
    public Credentials create(String email, Instant now) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        UUID id = UUID.randomUUID();
        sessions.put(hash(raw), new Session(id, email.trim().toLowerCase(Locale.ROOT), now));
        return new Credentials(id, raw);
    }

    @Override
    public Optional<Session> find(String cookieToken, Instant now) {
        if (cookieToken == null || cookieToken.isBlank()) return Optional.empty();
        String key = hash(cookieToken);
        Session session = sessions.get(key);
        if (session == null) return Optional.empty();
        if (!now.isBefore(session.lastActivityAt().plus(IDLE_LIMIT))) {
            sessions.remove(key, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    @Override
    public Optional<Session> recordActivity(String cookieToken, Instant now) {
        if (cookieToken == null || cookieToken.isBlank()) return Optional.empty();
        String key = hash(cookieToken);
        final Session[] updated = new Session[1];
        sessions.computeIfPresent(key, (ignored, session) -> {
            if (!now.isBefore(session.lastActivityAt().plus(IDLE_LIMIT))) return null;
            Session next = new Session(session.sessionId(), session.email(), now);
            updated[0] = next;
            return next;
        });
        return Optional.ofNullable(updated[0]);
    }

    @Override
    public boolean revoke(String cookieToken) {
        if (cookieToken == null || cookieToken.isBlank()) return false;
        return sessions.remove(hash(cookieToken)) != null;
    }

    private static String hash(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
