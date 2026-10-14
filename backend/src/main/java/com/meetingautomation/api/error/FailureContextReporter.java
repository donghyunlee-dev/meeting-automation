package com.meetingautomation.api.error;

import com.meetingautomation.notification.AdminIncidentNotifier;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/** Preserves the local incident record and isolates the optional Admin notification side effect. */
@Component
public final class FailureContextReporter {
    private final AdminIncidentNotifier notifier;

    public FailureContextReporter(AdminIncidentNotifier notifier) {
        this.notifier = notifier;
    }

    public void report(FailureContext context, Logger logger) {
        context.logWarning(logger);
        try {
            notifier.notifyIncident(context);
        } catch (RuntimeException ignored) {
            // A failed best-effort notification cannot replace or recursively report the source failure.
        }
    }
}
