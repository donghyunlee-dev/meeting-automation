package com.meetingautomation.document.bootstrap;

import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.settings.SettingsException;
import java.util.*;

/** Explicit initialization is intentionally separate from read-only hierarchy discovery. */
public final class DocumentStructureInitializer {
    private final BootstrapPages pages;
    public DocumentStructureInitializer(BootstrapPages pages) { this.pages = pages; }

    public DocumentStructure initializeStructure(String parent, String reuseRoot, String operationId, BootstrapJournal journal) {
        // Resolve every possibly-sent write before considering any new page creation.
        for (String step : List.of("ROOT","MEETINGS","PARTICIPANTS")) {
            if (journal.find(step)!=null) reconcile(step,journal);
        }
        String root;
        if (journal.find("ROOT") != null) {
            root = reconcile("ROOT", journal);
        } else if (reuseRoot != null) {
            unique(pages.children(parent), "Meeting Automation", reuseRoot);
            verify(pages.read(reuseRoot), parent, "Meeting Automation", null, false);
            journal.intent("ROOT", parent, "Meeting Automation", null);
            journal.completed("ROOT", reuseRoot);
            root = reuseRoot;
        } else {
            if (unique(pages.children(parent), "Meeting Automation", null) != null) throw conflict();
            root = ensure("ROOT", parent, "Meeting Automation", operationId, journal, null);
        }
        List<BootstrapPages.Page> children = pages.children(root);
        // Validate both titles before partially completing an explicitly reused hierarchy.
        var meetings = journal.find("MEETINGS")==null ? unique(children, "Meetings", null) : null;
        var participants = journal.find("PARTICIPANTS")==null ? unique(children, "Participants", null) : null;
        String meetingsId = ensure("MEETINGS", root, "Meetings", operationId, journal, meetings);
        String participantsId = ensure("PARTICIPANTS", root, "Participants", operationId, journal, participants);
        verify(pages.read(root), parent, "Meeting Automation", null, false);
        unique(pages.children(root), "Meetings", meetingsId);
        unique(pages.children(root), "Participants", participantsId);
        verify(pages.read(meetingsId), root, "Meetings", null, false);
        verify(pages.read(participantsId), root, "Participants", null, false);
        return new DocumentStructure(root, meetingsId, participantsId);
    }
    private String ensure(String step, String parent, String title, String operationId, BootstrapJournal journal, BootstrapPages.Page existing) {
        if (journal.find(step) != null) return reconcile(step, journal);
        String marker = existing == null ? "meeting-automation/bootstrap/" + operationId + "/" + step : null;
        journal.intent(step, parent, title, marker);
        String id = existing == null ? pages.create(parent, title, marker) : existing.id();
        verify(pages.read(id), parent, title, marker, true);
        journal.completed(step, id);
        return id;
    }
    private String reconcile(String step, BootstrapJournal journal) {
        var entry = journal.find(step);
        var candidates = pages.children(entry.parentId()).stream().filter(p -> entry.title().equals(p.title())).toList();
        if (candidates.size() != 1) throw reconciliation();
        var found = candidates.getFirst();
        if (entry.pageId() != null && !entry.pageId().equals(found.id())) throw reconciliation();
        verify(pages.read(found.id()), entry.parentId(), entry.title(), entry.marker(), true);
        if (entry.pageId()==null) journal.completed(step, found.id());
        return found.id();
    }
    private static BootstrapPages.Page unique(List<BootstrapPages.Page> children, String title, String expectedId) {
        var matches = children.stream().filter(p -> title.equals(p.title())).toList();
        if (matches.size() > 1 || (!matches.isEmpty() && !"page".equals(matches.getFirst().type()))) throw conflict();
        if (expectedId != null && (matches.size() != 1 || !expectedId.equals(matches.getFirst().id()))) throw conflict();
        return matches.isEmpty() ? null : matches.getFirst();
    }
    private static void verify(BootstrapPages.Page page, String parent, String title, String marker, boolean recovering) {
        if (page == null || !"page".equals(page.type()) || !Objects.equals(parent,page.parentId()) || !title.equals(page.title())
                || (marker != null && !marker.equals(page.marker()))) {
            throw recovering ? reconciliation() : conflict();
        }
    }
    private static SettingsException conflict() { return new SettingsException("DOCUMENT_STRUCTURE_CONFLICT",409); }
    private static SettingsException reconciliation() { return new SettingsException("DOCUMENT_RECONCILIATION_REQUIRED",409); }
}
