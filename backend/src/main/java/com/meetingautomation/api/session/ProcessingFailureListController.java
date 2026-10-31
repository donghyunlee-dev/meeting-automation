package com.meetingautomation.api.session;

import java.time.Instant;
import java.util.Comparator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meeting-sessions")
public final class ProcessingFailureListController {
    private final MeetingSessionStore sessions;

    public ProcessingFailureListController(MeetingSessionStore sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/processing-failures")
    public ProcessingFailureListResponse list() {
        Instant now = Instant.now();
        var items = sessions.findAll().stream()
                .filter(session -> "PROCESSING_FAILED".equals(session.status())
                        && session.processingFailure() != null)
                .sorted(Comparator.comparing(MeetingSession::createdAt).reversed())
                .map(session -> {
                    MeetingSessionStatusResponse.Data status = MeetingSessionStatusResponse
                            .from(session, null, null, null, now).data();
                    ProcessingFailure failure = session.processingFailure();
                    return new ProcessingFailureListResponse.Item(session.sessionId(), session.version(),
                            session.meeting().title(), session.meetingAt(), failure.stage(), failure.errorCode(),
                            failure.audioExpiresAt(), status.allowedActions());
                })
                .toList();
        return new ProcessingFailureListResponse(new ProcessingFailureListResponse.Data(items));
    }
}
