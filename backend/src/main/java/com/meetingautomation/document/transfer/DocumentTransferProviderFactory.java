package com.meetingautomation.document.transfer;

import com.meetingautomation.document.settings.GlobalDocumentSettings;

public interface DocumentTransferProviderFactory {
    DocumentTransferPort create(GlobalDocumentSettings.Connection connection);
}
