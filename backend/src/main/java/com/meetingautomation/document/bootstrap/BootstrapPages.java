package com.meetingautomation.document.bootstrap;

import java.util.List;

/** Page-only provider primitives. Reads also retain non-page siblings for conflict detection. */
public interface BootstrapPages {
    record Page(String id, String parentId, String title, String type, String marker) { }
    List<Page> children(String parentId);
    Page read(String id);
    String create(String parentId, String title, String marker);
    default void ensureMeetingIndex(String meetingDataPageId) { }
    default String create(String parentId, String title, String marker, String markdownContent) {
        return create(parentId, title, marker);
    }
}
