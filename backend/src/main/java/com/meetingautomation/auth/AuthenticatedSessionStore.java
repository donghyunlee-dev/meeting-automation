package com.meetingautomation.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthenticatedSessionStore {
    record Credentials(UUID sessionId, String cookieToken) { }
    record Session(UUID sessionId, String email, Instant lastActivityAt) { }
    Credentials create(String email, Instant now);
    Optional<Session> find(String cookieToken, Instant now);
    Optional<Session> recordActivity(String cookieToken, Instant now);
    boolean revoke(String cookieToken);
}
