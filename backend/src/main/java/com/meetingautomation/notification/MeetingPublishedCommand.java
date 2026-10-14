package com.meetingautomation.notification;

import java.time.OffsetDateTime;

/** Contains only the approved meeting notification fields; no transcript or participant data. */
public record MeetingPublishedCommand(String title, OffsetDateTime meetingAt, String documentUrl) { }
