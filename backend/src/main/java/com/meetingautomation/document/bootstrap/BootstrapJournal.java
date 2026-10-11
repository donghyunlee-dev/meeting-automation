package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.settings.GlobalDocumentSettings.Creation;

/** Intent must become durable before any page creation request begins. */
public interface BootstrapJournal {
    Creation find(String step);
    void intent(String step, String parentId, String title, String marker);
    void completed(String step, String pageId);
}
