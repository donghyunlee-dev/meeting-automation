package com.meetingautomation.document.settings;

/** Read-only port: implementation cannot create or update provider pages. */
public interface DocumentConnectionTester {
    void verify(GlobalDocumentSettings.Draft draft);
}
