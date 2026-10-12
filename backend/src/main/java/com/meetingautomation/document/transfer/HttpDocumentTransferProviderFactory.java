package com.meetingautomation.document.transfer;

import com.meetingautomation.document.settings.DocumentProviderFactory;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.SettingsException;
import org.springframework.stereotype.Component;

@Component
public final class HttpDocumentTransferProviderFactory implements DocumentTransferProviderFactory {
    private final DocumentProviderFactory providers;

    public HttpDocumentTransferProviderFactory(DocumentProviderFactory providers) {
        this.providers = providers;
    }

    @Override public DocumentTransferPort create(GlobalDocumentSettings.Connection connection) {
        var provider = providers.create(connection);
        if (provider instanceof DocumentTransferPort transfer) return transfer;
        throw SettingsException.storage();
    }
}
