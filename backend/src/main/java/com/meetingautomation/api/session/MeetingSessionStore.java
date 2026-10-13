package com.meetingautomation.api.session;

import com.meetingautomation.document.DocumentConnectionGate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Process-local session storage. This implementation intentionally has no persistence. */
@Component
public final class MeetingSessionStore {
    private final Map<String, MeetingSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, DocumentConnectionGate.SessionLease> registrations = new ConcurrentHashMap<>();
    private final Map<String, UploadPolicy> uploadPolicies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    public <T> T withSessionLock(String sessionId, Supplier<T> operation) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    public synchronized MeetingSession save(MeetingSession session, DocumentConnectionGate.SessionLease registration) {
        return save(session, registration, null);
    }

    public synchronized MeetingSession save(
            MeetingSession session,
            DocumentConnectionGate.SessionLease registration,
            UploadPolicy uploadPolicy) {
        sessions.put(session.sessionId(), session);
        if (registration != null) registrations.put(session.sessionId(), registration);
        else registrations.remove(session.sessionId());
        if (uploadPolicy != null) uploadPolicies.put(session.sessionId(), uploadPolicy);
        else uploadPolicies.remove(session.sessionId());
        return session;
    }

    /** Removes an in-memory session and releases its shared document-connection registration. */
    public boolean remove(String sessionId) {
        return withSessionLock(sessionId, () -> {
            MeetingSession removed;
            DocumentConnectionGate.SessionLease registration;
            synchronized (this) {
                removed = sessions.remove(sessionId);
                registration = registrations.remove(sessionId);
                uploadPolicies.remove(sessionId);
            }
            if (registration != null) registration.close();
            return removed != null;
        });
    }

    public Set<String> sessionIds() { return Set.copyOf(sessions.keySet()); }

    public int size() {
        return sessions.size();
    }

    public Optional<MeetingSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public Optional<UploadPolicy> findUploadPolicy(String sessionId) {
        return Optional.ofNullable(uploadPolicies.get(sessionId));
    }

    public synchronized MeetingSession transitionStatus(String sessionId, String expectedStatus, String newStatus) {
        MeetingSession current = sessions.get(sessionId);
        if (current == null) throw new IllegalStateException("Session is no longer available.");
        if (!current.status().equals(expectedStatus)) throw new SessionStateConflictException();
        MeetingSession updated = new MeetingSession(current.sessionId(), Math.addExact(current.version(), 1),
                newStatus, current.documentConnectionVersion(), current.meeting());
        sessions.put(sessionId, updated);
        return updated;
    }
}
