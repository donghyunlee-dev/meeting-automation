package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.settings.GlobalDocumentSettings;

public interface BootstrapProviderFactory {
    BootstrapPages create(GlobalDocumentSettings.Draft draft);
}
