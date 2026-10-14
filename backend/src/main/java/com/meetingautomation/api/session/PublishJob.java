package com.meetingautomation.api.session;

import com.meetingautomation.document.DocumentConnectionGate;
import com.meetingautomation.document.settings.GlobalDocumentSettings;

record PublishJob(String operationId, MeetingSession session, GlobalDocumentSettings.Connection connection,
        DocumentConnectionGate.WriteLease lease, String traceId) { }
