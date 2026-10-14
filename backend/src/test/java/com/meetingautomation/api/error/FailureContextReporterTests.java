package com.meetingautomation.api.error;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.meetingautomation.notification.AdminIncidentNotifier;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FailureContextReporterTests {
    @Test
    void notifierFailureIsContainedAndNeverStartsRecursiveIncidentReporting() {
        AtomicInteger calls = new AtomicInteger();
        AdminIncidentNotifier notifier = context -> {
            calls.incrementAndGet();
            throw new IllegalStateException("private webhook URL and response body");
        };
        FailureContextReporter reporter = new FailureContextReporter(notifier);
        FailureContext context = FailureContext.from("ms_report_1", "EMAIL_DELIVERY", "tr_report_1",
                "EMAIL_FAILED", false, Instant.parse("2026-10-14T10:00:00Z")).orElseThrow();

        assertThatCode(() -> reporter.report(context, LoggerFactory.getLogger(FailureContextReporterTests.class)))
                .doesNotThrowAnyException();

        org.assertj.core.api.Assertions.assertThat(calls).hasValue(1);
    }

    @Test
    void reporterAlwaysKeepsOriginalSafeStructuredLog() {
        AdminIncidentNotifier notifier = mock(AdminIncidentNotifier.class);
        FailureContextReporter reporter = new FailureContextReporter(notifier);
        FailureContext context = FailureContext.from("ms_report_2", "NOTIFICATION", "tr_report_2",
                "NOTIFICATION_FAILED", true, Instant.parse("2026-10-14T10:00:00Z")).orElseThrow();
        Logger logger = LoggerFactory.getLogger(FailureContextReporterTests.class);

        reporter.report(context, logger);

        verify(notifier).notifyIncident(context);
    }
}
