package com.meetingautomation.document;

import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import com.meetingautomation.document.settings.SettingsException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Coordinates connection changes with session registration and in-flight writes. */
@Component
public final class DocumentConnectionGate {
    private static final Set<String> TERMINAL_OPERATION_STATES = Set.of("SUCCEEDED", "CANCELLED");

    private final GlobalSettingsStore store;
    private final Object lock = new Object();
    private final Set<String> sessions = new HashSet<>();
    private int activeWrites;
    private boolean switchHeld;

    public DocumentConnectionGate(GlobalSettingsStore store) {
        this.store = store;
    }

    /** Session registration shares the same boundary used to acquire a switch lease. */
    public SessionLease beginSession(long expectedConnectionVersion, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw SettingsException.invalid();
        synchronized (lock) {
            var state = store.read();
            rejectIfChanging(state);
            var active = state.active();
            if (active == null) throw new SettingsException("DOCUMENT_SETUP_REQUIRED", 409);
            if (active.connectionVersion() != expectedConnectionVersion)
                throw new SettingsException("DOCUMENT_CONNECTION_CHANGED", 409);
            if (!sessions.add(sessionId)) throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            return new SessionLease(sessionId);
        }
    }

    /** A write lease remains held across the provider request so activation cannot race it. */
    public WriteLease beginWrite(long expectedConnectionVersion) {
        synchronized (lock) {
            var state = store.read();
            rejectIfChanging(state);
            var active = state.active();
            if (active == null) throw new SettingsException("DOCUMENT_SETUP_REQUIRED", 409);
            if (active.connectionVersion() != expectedConnectionVersion)
                throw new SettingsException("DOCUMENT_CONNECTION_CHANGED", 409);
            activeWrites++;
            return new WriteLease();
        }
    }

    /** Call before persisting a switch operation; callers keep the lease for its lifetime. */
    public SwitchLease beginSwitch(long expectedSettingsVersion) {
        synchronized (lock) {
            var state = store.read();
            if (state.version() != expectedSettingsVersion)
                throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
            rejectIfChanging(state);
            if (switchHeld || !sessions.isEmpty() || activeWrites != 0)
                throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            switchHeld = true;
            return new SwitchLease();
        }
    }

    /** Reacquires a durable failed/interrupted switch without opening a session/write race. */
    public SwitchLease resumeSwitch(long expectedSettingsVersion, String operationId) {
        synchronized (lock) {
            var state = store.read();
            if (state.version() != expectedSettingsVersion)
                throw new SettingsException("DOCUMENT_SETTINGS_VERSION_CONFLICT", 412);
            var operation = state.operation();
            if (operation == null || !operation.operationId().equals(operationId)
                    || !"SWITCH".equals(operation.type())
                    || !("INTERRUPTED".equals(operation.status()) || "FAILED".equals(operation.status()) && operation.retryable()))
                throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            if (switchHeld || !sessions.isEmpty() || activeWrites != 0)
                throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
            switchHeld = true;
            return new SwitchLease();
        }
    }

    private void rejectIfChanging(GlobalDocumentSettings state) {
        var operation = state.operation();
        if (switchHeld || operation != null && !TERMINAL_OPERATION_STATES.contains(operation.status()))
            throw new SettingsException("DOCUMENT_CHANGE_BUSY", 409);
    }

    public final class SessionLease implements AutoCloseable {
        private final String sessionId;
        private boolean closed;
        private SessionLease(String sessionId) { this.sessionId = sessionId; }
        @Override public void close() {
            synchronized (lock) {
                if (!closed) {
                    sessions.remove(sessionId);
                    closed = true;
                }
            }
        }
    }

    public final class WriteLease implements AutoCloseable {
        private boolean closed;
        private WriteLease() { }
        @Override public void close() {
            synchronized (lock) {
                if (!closed) {
                    activeWrites--;
                    closed = true;
                }
            }
        }
    }

    public final class SwitchLease implements AutoCloseable {
        private boolean closed;
        private SwitchLease() { }
        @Override public void close() {
            synchronized (lock) {
                if (!closed) {
                    switchHeld = false;
                    closed = true;
                }
            }
        }
    }
}
