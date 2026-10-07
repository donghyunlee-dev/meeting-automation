package com.meetingautomation.document;

/** Provider-neutral contract for connection health and root page hierarchy discovery. */
public interface DocumentStructureProvider {
    ProviderHealth validateConnection();

    DocumentStructure discoverStructure(String rootId);
}
