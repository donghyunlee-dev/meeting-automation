package com.meetingautomation.api.session;

import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class MeetingSessionStatusService {
    private final MeetingSessionStore sessionStore;
    private final Clock clock;

    @Autowired
    public MeetingSessionStatusService(MeetingSessionStore sessionStore) {
        this(sessionStore, Clock.systemUTC());
    }

    MeetingSessionStatusService(MeetingSessionStore sessionStore, Clock clock) {
        this.sessionStore = sessionStore;
        this.clock = clock;
    }

    public MeetingSessionStatusResponse get(String sessionId) {
        return sessionStore.withSessionLock(sessionId, () -> {
            MeetingSession session = sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
            return MeetingSessionStatusResponse.from(session,
                    sessionStore.findLastOperation(sessionId).orElse(null), sessionStore.findPublishSnapshot(sessionId),
                    sessionStore.findFailureFinalization(sessionId).orElse(null), clock.instant());
        });
    }
}

