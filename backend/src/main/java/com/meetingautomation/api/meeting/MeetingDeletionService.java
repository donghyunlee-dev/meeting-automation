package com.meetingautomation.api.meeting;

import com.meetingautomation.document.*;
import com.meetingautomation.document.settings.SettingsException;
import org.springframework.stereotype.Service;

@Service
public final class MeetingDeletionService {
    private final DocumentProviderResolver resolver;
    private final DocumentConnectionGate gate;
    public MeetingDeletionService(DocumentProviderResolver resolver, DocumentConnectionGate gate) {
        this.resolver = resolver;
        this.gate = gate;
    }
    public void delete(String documentId) {
        resolver.requireActive();
        var connection = resolver.active().orElseThrow(() -> new SettingsException("DOCUMENT_SETUP_REQUIRED", 409));
        try (var ignored = gate.beginWrite(connection.connectionVersion())) {
            var provider = resolver.selectedProvider().filter(MeetingDeletingProvider.class::isInstance)
                    .map(MeetingDeletingProvider.class::cast)
                    .orElseThrow(() -> DocumentProviderException.documentFailed(false));
            try { provider.deleteMeeting(documentId); }
            catch (DocumentProviderException failure) { throw failure; }
            catch (RuntimeException failure) { throw DocumentProviderException.documentFailed(false, failure); }
        }
    }
}
