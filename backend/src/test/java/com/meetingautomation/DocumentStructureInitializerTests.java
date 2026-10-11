package com.meetingautomation;

import com.meetingautomation.document.bootstrap.*;
import com.meetingautomation.document.settings.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentStructureInitializerTests {
    @Test void bootNotionCreatesOnlyThreeMarkedPagesInOrder() {
        var pages = new Pages(); var journal = new Journal();
        var structure = new DocumentStructureInitializer(pages).initializeStructure("parent", null, "operation", journal);
        assertThat(pages.created).containsExactly("Meeting Automation", "Meetings", "Participants");
        assertThat(structure.rootId()).isEqualTo("page1");
        assertThat(pages.nodes.get("page2").parentId()).isEqualTo("page1");
        assertThat(journal.entries).hasSize(3).allSatisfy((step, entry) -> assertThat(entry.pageId()).isNotBlank());
    }
    @Test void bootReuseCompletesOnlyMissingChildAndNeverWritesCompleteHierarchy() {
        var pages = new Pages(); pages.add("root", "parent", "Meeting Automation", "page", "");
        pages.add("meetings", "root", "Meetings", "page", "");
        var initializer = new DocumentStructureInitializer(pages);
        initializer.initializeStructure("parent", "root", "operation", new Journal());
        assertThat(pages.created).containsExactly("Participants");
        pages.created.clear();
        initializer.initializeStructure("parent", "root", "other", new Journal());
        assertThat(pages.created).isEmpty();
    }
    @Test void bootDuplicateAndWrongTypeFailBeforeAnyWrite() {
        for (String type : List.of("page", "database", "folder")) {
            var pages = new Pages(); pages.add("root", "parent", "Meeting Automation", "page", "");
            pages.add("one", "root", "Meetings", "page", "");
            pages.add("two", "root", "Meetings", type, "");
            assertThatThrownBy(() -> new DocumentStructureInitializer(pages)
                .initializeStructure("parent", "root", "operation", new Journal()))
                .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_STRUCTURE_CONFLICT");
            assertThat(pages.created).isEmpty();
        }
    }
    @Test void bootRecoveryReconcilesLostCreateResponseUsingInitialMarker() {
        var pages = new Pages(); var journal = new Journal(); pages.loseNextResponse = true;
        var initializer = new DocumentStructureInitializer(pages);
        assertThatThrownBy(() -> initializer.initializeStructure("parent", null, "operation", journal))
            .isInstanceOf(SettingsException.class);
        assertThat(journal.entries.get("ROOT").pageId()).isNull();
        var structure = initializer.initializeStructure("parent", null, "operation", journal);
        assertThat(structure.rootId()).isEqualTo("page1");
        assertThat(pages.created).containsExactly("Meeting Automation", "Meetings", "Participants");
    }
    @Test void bootRecoveryNeverRecreatesAnUnresolvedIntent() {
        var pages = new Pages(); var journal = new Journal();
        journal.intent("ROOT", "parent", "Meeting Automation", "meeting-automation/bootstrap/operation/ROOT");
        assertThatThrownBy(() -> new DocumentStructureInitializer(pages)
            .initializeStructure("parent", null, "operation", journal))
            .isInstanceOf(SettingsException.class).extracting("code").isEqualTo("DOCUMENT_RECONCILIATION_REQUIRED");
        assertThat(pages.created).isEmpty();
    }
    @Test void bootRecoveryReconcilesEveryPriorIntentBeforeCreatingAnyMissingPage() {
        var pages=new Pages(); var journal=new Journal();
        String marker="meeting-automation/bootstrap/operation/ROOT";
        pages.add("root","parent","Meeting Automation","page",marker);
        journal.intent("ROOT","parent","Meeting Automation",marker); journal.completed("ROOT","root");
        journal.intent("PARTICIPANTS","root","Participants","meeting-automation/bootstrap/operation/PARTICIPANTS");
        assertThatThrownBy(() -> new DocumentStructureInitializer(pages).initializeStructure("parent",null,"operation",journal))
            .isInstanceOf(SettingsException.class);
        assertThat(pages.created).isEmpty();
    }
    static final class Journal implements BootstrapJournal {
        final Map<String, GlobalDocumentSettings.Creation> entries = new LinkedHashMap<>();
        public GlobalDocumentSettings.Creation find(String step) { return entries.get(step); }
        public void intent(String step, String parent, String title, String marker) {
            entries.put(step, new GlobalDocumentSettings.Creation(parent, title, marker, null));
        }
        public void completed(String step, String id) {
            var entry = entries.get(step);
            entries.put(step, new GlobalDocumentSettings.Creation(entry.parentId(), entry.title(), entry.marker(), id));
        }
    }
    static final class Pages implements BootstrapPages {
        final Map<String, Page> nodes = new LinkedHashMap<>();
        final List<String> created = new ArrayList<>(); boolean loseNextResponse;
        void add(String id, String parent, String title, String type, String marker) {
            nodes.put(id, new Page(id, parent, title, type, marker));
        }
        public List<Page> children(String parent) { return nodes.values().stream().filter(p -> Objects.equals(parent,p.parentId())).toList(); }
        public Page read(String id) { return nodes.get(id); }
        public String create(String parent, String title, String marker) {
            String id = "page" + (created.size()+1); created.add(title); add(id,parent,title,"page",marker);
            if (loseNextResponse) { loseNextResponse=false; throw new SettingsException("DOCUMENT_INITIALIZATION_FAILED",502); }
            return id;
        }
    }
}
