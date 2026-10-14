package com.meetingautomation.notification;

import com.meetingautomation.api.error.FailureContext;

/** Best-effort side effect for a sanitized operational failure context. */
public interface AdminIncidentNotifier {
    void notifyIncident(FailureContext context);
}
