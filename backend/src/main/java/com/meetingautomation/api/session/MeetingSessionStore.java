package com.meetingautomation.api.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Process-local session storage. This implementation intentionally has no persistence. */
@Component
public final class MeetingSessionStore {
    private final Map<String, MeetingSession> sessions = new ConcurrentHashMap<>();

    public MeetingSession save(MeetingSession session) {
        sessions.put(session.sessionId(), session);
        return session;
    }

    public int size() {
        return sessions.size();
    }
}
