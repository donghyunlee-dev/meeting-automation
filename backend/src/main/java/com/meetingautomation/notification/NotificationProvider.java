package com.meetingautomation.notification;

import com.meetingautomation.api.health.IntegrationHealthStatus;

/** Provider-neutral port for best-effort meeting publication notifications. */
public interface NotificationProvider {
    IntegrationHealthStatus validateConnection();

    DeliveryResult sendMeetingPublished(MeetingPublishedCommand command);
}
