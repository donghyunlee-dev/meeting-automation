package com.meetingautomation.api.processing;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.document.MeetingDocumentRef;

@FunctionalInterface
public interface FailureDocumentWriter {
    MeetingDocumentRef write(MeetingSession session, String audioDisposition);
}
