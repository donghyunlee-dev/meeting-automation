package com.meetingautomation.api.session;

import com.meetingautomation.document.MeetingDocumentRef;
import java.time.Instant;

public record FailureFinalization(String audioDisposition, Instant completedAt, MeetingDocumentRef document) { }
