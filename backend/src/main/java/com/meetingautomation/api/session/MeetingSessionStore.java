package com.meetingautomation.api.session;

import com.meetingautomation.document.DocumentConnectionGate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Process-local session storage. This implementation intentionally has no persistence. */
@Component
public final class MeetingSessionStore {
    private final Map<String, MeetingSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, DocumentConnectionGate.SessionLease> registrations = new ConcurrentHashMap<>();

    public synchronized MeetingSession save(MeetingSession session, DocumentConnectionGate.SessionLease registration) {
        sessions.put(session.sessionId(), session);
        registrations.put(session.sessionId(), registration);
        return session;
    }

    /** Removes an in-memory session and releases its shared document-connection registration. */
    public synchronized boolean remove(String sessionId) {
        MeetingSession removed = sessions.remove(sessionId);
        DocumentConnectionGate.SessionLease registration = registrations.remove(sessionId);
        if (registration != null) registration.close();
        return removed != null;
    }

    public Set<String> sessionIds() { return Set.copyOf(sessions.keySet()); }

    public int size() {
        return sessions.size();
    }

    public Optional<MeetingSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
}
