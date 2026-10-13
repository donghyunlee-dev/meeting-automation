package com.meetingautomation.api.session;

import com.meetingautomation.api.audio.AudioSessionNotFoundException;
import org.springframework.stereotype.Service;

@Service
public final class MeetingSessionStatusService {
    private final MeetingSessionStore sessionStore;

    public MeetingSessionStatusService(MeetingSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public MeetingSessionStatusResponse get(String sessionId) {
        return sessionStore.withSessionLock(sessionId, () -> {
            MeetingSession session = sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
            return MeetingSessionStatusResponse.from(session);
        });
    }
}
