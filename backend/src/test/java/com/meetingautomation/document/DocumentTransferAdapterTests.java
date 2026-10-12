package com.meetingautomation.document;

import com.meetingautomation.document.confluence.ConfluencePageHierarchyAdapter;
import com.meetingautomation.document.notion.NotionPageHierarchyAdapter;
import com.meetingautomation.document.transfer.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DocumentTransferAdapterTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TransferCodec CODEC = new TransferCodec();

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void manifestExhaustsMultipleCursorsForBothRootsAndPreservesRevision(boolean notion) {
        Fixture f = new Fixture(notion, 205, null);
        TransferManifest manifest = f.port.readManifest();
        assertEquals(205, manifest.participants().size());
        assertEquals(205, manifest.meetings().size());
        assertEquals(205, manifest.meetings().stream().map(TransferManifest.Entry::sourceDocumentId).distinct().count());
        assertEquals(notion ? "2026-10-10T01:00:00.000Z" : "7", manifest.meetings().getFirst().sourceRevision());
        assertTrue(manifest.meetings().getFirst().canonicalDigest().matches("sha256:[0-9a-f]{64}"));
        assertTrue(f.requested.contains("participants:200"));
        assertTrue(f.requested.contains("meetings:200"));
        f.server.verify();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void invalidMarkersPagesReferencesAndCursorsFailWholeManifest(boolean notion) {
        for (String defect : List.of("missing", "duplicate", "malformed", "schema", "reference", "repeat", "cursor", "page", "duplicate-page")) {
            Fixture f = new Fixture(notion, 101, defect);
            assertThrows(TransferCodec.ValidationException.class, f.port::readManifest, defect);
            f.server.verify();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void importReadBackRetainsAbsentContentFailureAndSeparateMarker(boolean notion) {
        Fixture f = new Fixture(notion, 0, null);
        TransferRecord record = meeting("source", List.of(), new TransferMarker.Failure("TRANSCRIPTION", "AI_FAILED", "DELETED"));
        MeetingDocumentRef ref = f.port.importMeeting(record, Map.of(), CODEC.connectionFingerprint("notion", "source-root"));
        TransferRecord read = f.port.exportMeeting(ref.documentId());
        assertEquals(record.title(), read.title());
        assertNull(read.minutes()); assertNull(read.transcript());
        assertEquals(record.marker().failure(), read.marker().failure());
        assertEquals(record.marker().meetingAt(), read.marker().meetingAt());
        assertEquals(CODEC.canonicalDigest(record), CODEC.canonicalDigest(read));
        assertEquals(CODEC.transferKey(CODEC.connectionFingerprint("notion", "source-root"), "source"), read.marker().transfer().transferKey());
        assertTrue(f.createdBody.contains(notion ? "\"code\"" : "<pre>"));
        assertFalse(CODEC.encodeMarker(read.marker()).contains(record.title()));
        f.server.verify();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void importsParticipantAndFullMeetingBodyWithEveryReferenceRewritten(boolean notion) {
        Fixture f = new Fixture(notion, 0, null);
        String fingerprint = CODEC.connectionFingerprint("notion", "source-root");
        Participant participant = f.port.importParticipant(participant("old"), fingerprint);
        TransferRecord simple = meeting("source", List.of("old"), null);
        StructuredMinutes minutes = new StructuredMinutes("project.md", "1.0.0", "Summary <&>\r\nsecond line", List.of("Discuss"), List.of("Decide"),
                List.of(new StructuredMinutes.ActionItem("old", "Task", java.time.LocalDate.of(2026, 11, 1))), List.of("Follow"));
        TransferRecord source = new TransferRecord("source", "r1", null, simple.marker(), "Synthetic <&> meeting", minutes,
                List.of(new TranscriptSegment("seg", "speaker", 0, 42, "Transcript <&>\r\nline")), List.of(new ParticipantSummary("old", "Ada")), null);
        MeetingDocumentRef ref = f.port.importMeeting(source, Map.of("old", participant.id()), fingerprint);
        TransferRecord read = f.port.exportMeeting(ref.documentId());
        assertEquals(source.title(), read.title()); assertEquals(source.transcript(), read.transcript());
        assertEquals(source.minutes().summary(), read.minutes().summary());
        assertEquals("project.md", read.minutes().templateId()); assertEquals("default.md", read.marker().templateId());
        assertEquals(participant.id(), read.minutes().actionItems().getFirst().ownerParticipantId());
        assertEquals(participant.id(), read.marker().speakers().getFirst().participantId());
        assertEquals(List.of(participant.id()), read.marker().participantIds());
        assertEquals(CODEC.canonicalDigest(source), CODEC.canonicalDigest(read));
        f.server.verify();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void reusesVerifiedParticipantAndMeetingWithoutSecondCreate(boolean notion) {
        Fixture f = new Fixture(notion, 0, null); String fingerprint = CODEC.connectionFingerprint("notion", "source-root");
        Participant first = f.port.importParticipant(participant("old"), fingerprint);
        assertEquals(first, f.port.importParticipant(participant("old"), fingerprint));
        TransferRecord record = meeting("source", List.of("old"), null);
        MeetingDocumentRef meeting = f.port.importMeeting(record, Map.of("old", first.id()), fingerprint);
        assertEquals(meeting, f.port.importMeeting(record, Map.of("old", first.id()), fingerprint));
        assertEquals(2, f.saved.size()); f.server.verify();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void markerRichTextFragmentsAndConfluenceCodeMacroAreJoinedInOrder(boolean notion) {
        Fixture f = new Fixture(notion, 1, "fragments");
        assertEquals(1, f.port.readManifest().meetings().size()); f.server.verify();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ambiguousCreateIsReconciledWithoutRetryAndAbsentCreateHasTypedFailure(boolean notion) {
        String fingerprint = CODEC.connectionFingerprint("notion", "source-root");
        Fixture recovered = new Fixture(notion, 0, "ambiguous");
        assertEquals("9000", recovered.port.importParticipant(participant("old"), fingerprint).id());
        assertEquals(1, recovered.creates);
        Fixture absent = new Fixture(notion, 0, "ambiguous-absent");
        var failure = assertThrows(TransferAdapterException.class, () -> absent.port.importParticipant(participant("old"), fingerprint));
        assertEquals(TransferAdapterException.Code.RECONCILIATION_REQUIRED, failure.code()); assertEquals(1, absent.creates);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void changedContentForSameTransferKeyIsTypedConflictWithoutOverwrite(boolean notion) {
        Fixture f = new Fixture(notion, 0, null); String fingerprint = CODEC.connectionFingerprint("notion", "source-root");
        TransferRecord first = meeting("source", List.of(), null); f.port.importMeeting(first, Map.of(), fingerprint);
        TransferRecord changed = new TransferRecord(first.sourceDocumentId(), first.sourceRevision(), null, first.marker(), "Changed title", null, null, List.of(), null);
        var failure = assertThrows(TransferAdapterException.class, () -> f.port.importMeeting(changed, Map.of(), fingerprint));
        assertEquals(TransferAdapterException.Code.CONFLICT, failure.code()); assertEquals(1, f.creates);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void readBackMismatchHasTypedConflict(boolean notion) {
        Fixture f = new Fixture(notion, 0, "readback");
        var failure = assertThrows(TransferAdapterException.class, () -> f.port.importMeeting(meeting("source", List.of(), null), Map.of(), CODEC.connectionFingerprint("notion", "source-root")));
        assertEquals(TransferAdapterException.Code.CONFLICT, failure.code()); assertEquals(1, f.creates);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ordinaryParticipantCreateWritesExactlyOneSupportedMarker(boolean notion) {
        Fixture f = new Fixture(notion, 0, null);
        Participant created = f.creating.createParticipant("3", new CreateParticipantCommand("Ada", "ada@example.test"));
        TransferManifest manifest = f.port.readManifest();
        assertEquals(1, manifest.participants().size()); assertEquals(created, manifest.participants().getFirst().participant());
        assertNull(manifest.participants().getFirst().marker().transfer());
        assertEquals(CODEC.canonicalDigest(manifest.participants().getFirst()), manifest.participants().getFirst().marker().canonicalDigest());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ordinaryParticipantEditRefreshesSingleMarkerAndDigest(boolean notion) {
        Fixture f = new Fixture(notion, 0, null);
        Participant original = f.creating.createParticipant("3", new CreateParticipantCommand("Ada", "ada@example.test"));
        Participant updated = f.updating.updateParticipant(original.id(), new ParticipantUpdateCommand("Grace", "grace@example.test", original));
        TransferRecord read = f.port.readManifest().participants().getFirst();
        assertEquals(new Participant(original.id(), "Grace", "grace@example.test"), updated);
        assertEquals(updated, read.participant()); assertEquals(CODEC.canonicalDigest(read), read.marker().canonicalDigest());
        assertNull(read.marker().transfer());
    }

    @org.junit.jupiter.api.Test
    void interruptedNotionMarkerRefreshLeavesDetectableDigestMismatch() {
        Fixture f = new Fixture(true, 0, "marker-write-fails");
        Participant original = f.creating.createParticipant("3", new CreateParticipantCommand("Ada", "ada@example.test"));
        assertThrows(DocumentProviderException.class, () -> f.updating.updateParticipant(original.id(), new ParticipantUpdateCommand(null, "grace@example.test", original)));
        var failure = assertThrows(TransferCodec.ValidationException.class, f.port::readManifest);
        assertEquals(TransferCodec.Code.INVALID_RECORD, failure.code());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ordinaryParticipantEditRejectsLegacyUnmarkedPageBeforeWriting(boolean notion) {
        Fixture f = new Fixture(notion, 1, "missing");
        var failure = assertThrows(TransferCodec.ValidationException.class, () -> f.updating.updateParticipant("1000",
                new ParticipantUpdateCommand("Grace", null, new Participant("1000", "Ada", "ada@example.test"))));
        assertEquals(TransferCodec.Code.MISSING_MARKER, failure.code()); assertEquals(0, f.mutations);
    }

    @org.junit.jupiter.api.Test
    void notionLargeBodyAppendsInBatchesAndReadsEveryBodyCursorAndTextFragment() {
        Fixture f = new Fixture(true, 0, null); TransferRecord simple = meeting("source", List.of(), null);
        List<String> discussion = java.util.stream.IntStream.range(0, 205).mapToObj(i -> "Discussion " + i).toList();
        StructuredMinutes minutes = new StructuredMinutes("default.md", "1.0.0", "x".repeat(5001), discussion, List.of(), List.of(), List.of());
        TransferRecord source = new TransferRecord("source", "r1", null, simple.marker(), simple.title(), minutes, null, List.of(), null);
        MeetingDocumentRef created = f.port.importMeeting(source, Map.of(), CODEC.connectionFingerprint("confluence", "source-root"));
        TransferRecord read = f.port.exportMeeting(created.documentId());
        assertEquals(minutes, read.minutes()); assertTrue(f.requested.contains(created.documentId() + ":200")); assertEquals(2, f.mutations);
    }

    @org.junit.jupiter.api.Test
    void notionManifestPreservesOpaqueCursorWithoutDoubleEncoding() {
        Fixture f = new Fixture(true, 101, "opaque");
        assertEquals(101, f.port.readManifest().participants().size()); assertTrue(f.requested.contains("participants:100"));
    }

    @org.junit.jupiter.api.Test
    void notionRichTextChunksNeverSplitSupplementaryUnicodeAtTwoThousandBoundary() {
        Fixture f = new Fixture(true, 0, null); TransferRecord simple = meeting("source", List.of(), null);
        String summary = "x".repeat(1999) + "\uD83D\uDE80" + "y";
        StructuredMinutes minutes = new StructuredMinutes("default.md", "1.0.0", summary, List.of(), List.of(), List.of(), List.of());
        TransferRecord source = new TransferRecord("source", "r1", null, simple.marker(), simple.title(), minutes, null, List.of(), null);
        var created = f.port.importMeeting(source, Map.of(), CODEC.connectionFingerprint("confluence", "source-root"));
        List<String> summaryRuns = new ArrayList<>();
        for (var block : JSON.readTree(f.createdBody).path("children")) {
            var runs = block.path("paragraph").path("rich_text");
            String joined = String.join("", runs.valueStream().map(run -> run.path("text").path("content").asString()).toList());
            if (summary.equals(joined)) summaryRuns = runs.valueStream().map(run -> run.path("text").path("content").asString()).toList();
        }
        assertEquals(2, summaryRuns.size());
        for (String run : summaryRuns) {
            assertTrue(run.length() <= 2000);
            assertFalse(Character.isHighSurrogate(run.charAt(run.length() - 1)));
            assertFalse(Character.isLowSurrogate(run.charAt(0)));
        }
        assertEquals(summary, f.port.exportMeeting(created.documentId()).minutes().summary());
    }

    private static TransferRecord meeting(String id, List<String> ids, TransferMarker.Failure failure) {
        TransferMarker marker = new TransferMarker("meeting-automation", "meeting", "1.0", "session-" + id,
                OffsetDateTime.parse("2026-10-10T10:00:00+09:00"), "default.md", ids,
                ids.isEmpty() ? List.of() : List.of(new TransferMarker.Speaker("speaker", "Speaker", ids.getFirst())), failure, null, null);
        return new TransferRecord(id, "r1", null, marker, "Synthetic meeting", null, null,
                ids.stream().map(p -> new ParticipantSummary(p, "Ada")).toList(), null);
    }
    private static TransferRecord participant(String id) {
        TransferMarker marker = new TransferMarker("meeting-automation", "participant", "1.0", null, null, null, List.of(), List.of(), null,
                "sha256:" + "0".repeat(64), null);
        TransferRecord record = new TransferRecord(id, "r1", null, marker, "Ada", null, null, List.of(), new Participant(id, "Ada", "ada@example.test"));
        marker = new TransferMarker(marker.format(), marker.recordType(), marker.schemaVersion(), null, null, null, List.of(), List.of(), null, CODEC.canonicalDigest(record), null);
        return new TransferRecord(id, "r1", null, marker, null, null, null, List.of(), record.participant());
    }

    private static class Fixture {
        final boolean notion; final int count; final String defect;
        final MockRestServiceServer server; final DocumentTransferPort port; final ParticipantCreationProvider creating; final ParticipantUpdatingProvider updating;
        final Set<String> requested = new HashSet<>(); final Map<String, String> saved = new LinkedHashMap<>();
        String createdBody; int nextId = 9000; int creates; int mutations;
        Fixture(boolean notion, int count, String defect) {
            this.notion = notion; this.count = count; this.defect = defect;
            RestClient.Builder builder = RestClient.builder();
            server = MockRestServiceServer.bindTo(builder).build();
            Object adapter = notion ? new NotionPageHierarchyAdapter(builder, "synthetic-token", "1")
                    : new ConfluencePageHierarchyAdapter(builder, "https://synthetic.atlassian.net", "synthetic@example.test", "synthetic-token", "1");
            port = (DocumentTransferPort) adapter;
            creating = (ParticipantCreationProvider) adapter;
            updating = (ParticipantUpdatingProvider) adapter;
            server.expect(ExpectedCount.manyTimes(), request -> {}).andRespond(request -> {
                String path = request.getURI().getPath(); String query = Objects.toString(request.getURI().getQuery(), "");
                String response;
                if (request.getMethod() == org.springframework.http.HttpMethod.POST) {
                    creates++;
                    createdBody = new String(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes(), StandardCharsets.UTF_8);
                    String id = Integer.toString(nextId++); if (!"ambiguous-absent".equals(defect)) saved.put(id, createdBody);
                    if ("ambiguous".equals(defect) || "ambiguous-absent".equals(defect)) throw new java.io.IOException("Synthetic ambiguous write");
                    response = JSON.writeValueAsString(Map.of("id", id));
                } else if (request.getMethod() == org.springframework.http.HttpMethod.PATCH || request.getMethod() == org.springframework.http.HttpMethod.PUT) {
                    mutations++;
                    String payload = new String(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes(), StandardCharsets.UTF_8);
                    String target = path.substring(path.lastIndexOf('/') + 1);
                    if (notion && target.equals("children")) {
                        String[] values = path.split("/"); String id = values[values.length - 2];
                        @SuppressWarnings("unchecked") Map<String,Object> data = JSON.readValue(saved.get(id), Map.class);
                        @SuppressWarnings("unchecked") List<Object> children = (List<Object>) data.get("children");
                        @SuppressWarnings("unchecked") Map<String,Object> update = JSON.readValue(payload, Map.class);
                        children.addAll((List<?>) update.get("children")); saved.put(id, JSON.writeValueAsString(data));
                    } else if (notion && target.contains("-block-")) {
                        String[] values = target.split("-block-"); String id = values[0]; int index = Integer.parseInt(values[1]);
                        @SuppressWarnings("unchecked") Map<String,Object> data = JSON.readValue(saved.get(id), Map.class);
                        @SuppressWarnings("unchecked") List<Map<String,Object>> children = (List<Map<String,Object>>) data.get("children");
                        String type = children.get(index).get("type").toString();
                        if (type.equals("code") && "marker-write-fails".equals(defect)) throw new java.io.IOException("Synthetic marker write failure");
                        @SuppressWarnings("unchecked") Map<String,Object> update = JSON.readValue(payload, Map.class);
                        children.get(index).put(type, update.get(type)); saved.put(id, JSON.writeValueAsString(data));
                    } else if (notion) {
                        @SuppressWarnings("unchecked") Map<String,Object> data = JSON.readValue(saved.get(target), Map.class);
                        @SuppressWarnings("unchecked") Map<String,Object> update = JSON.readValue(payload, Map.class);
                        data.put("properties", update.get("properties")); saved.put(target, JSON.writeValueAsString(data));
                    } else saved.put(target, payload);
                    response = "{}";
                } else {
                    String[] parts = path.split("/"); String id = parts[parts.length - 1];
                    if (path.endsWith("children")) {
                        id = parts[parts.length - 2];
                        int start = query.contains("cursor=") ? Integer.parseInt(query.substring(query.indexOf("cursor=") + 7).replace("opaque cursor/", "")) : 0;
                        if (id.equals("1")) response = children(List.of(child("2", "Meetings"), child("3", "Participants")), null);
                        else if (id.equals("2") || id.equals("3")) {
                            String kind = id.equals("2") ? "meetings" : "participants"; requested.add(kind + ":" + start);
                            List<Object> children = new ArrayList<>();
                            for (int i = start; i < Math.min(start + 100, count); i++) children.add(child(Integer.toString((id.equals("2") ? 2000 : 1000) + i), id.equals("2") ? "Synthetic meeting" : "Ada"));
                            if ("duplicate-page".equals(defect) && start == 100) children.add(child("1000", "Ada"));
                            if (start == 0) for (var entry : saved.entrySet()) {
                                var data = JSON.readTree(entry.getValue());
                                String parent = notion ? data.path("parent").path("page_id").asString() : data.path("parentId").asString();
                                if (parent.equals(id)) children.add(child(entry.getKey(), notion ? data.path("properties").path("title").path("title").get(0).path("text").path("content").asString() : data.path("title").asString()));
                            }
                            String next = start + 100 < count ? Integer.toString(start + 100) : null;
                            if (next != null && "opaque".equals(defect)) next = "opaque cursor/" + next;
                            if (id.equals("3") && "repeat".equals(defect)) next = "100";
                            if (id.equals("3") && "cursor".equals(defect)) next = "";
                            response = children(children, next);
                        } else response = blocks(id, start);
                    } else response = page(id);
                }
                return withSuccess(response, MediaType.APPLICATION_JSON).createResponse(request);
            });
        }
        Object child(String id, String title) {
            return notion ? Map.of("id", id, "type", "child_page", "child_page", Map.of("title", title)) : Map.of("id", id, "type", "page", "title", title);
        }
        String children(List<Object> results, String next) {
            Map<String,Object> data = new LinkedHashMap<>(); data.put("results", results);
            if (notion) { data.put("has_more", next != null); data.put("next_cursor", next); }
            else if (next != null) data.put("_links", Map.of("next", "?cursor=" + next));
            return JSON.writeValueAsString(data);
        }
        String marker(String id) {
            String m = CODEC.encodeMarker(id.startsWith("1") ? participant(id).marker() : meeting(id, "reference".equals(defect) ? List.of("missing-person") : List.of(), null).marker());
            if (id.equals("1000")) {
                if ("missing".equals(defect)) return "{}";
                if ("duplicate".equals(defect)) return m + m;
                if ("malformed".equals(defect)) return "{";
                if ("schema".equals(defect)) return m.replace("1.0", "9.0");
            }
            return m;
        }
        List<Object> ordinary(String id) {
            List<Object> blocks = new ArrayList<>();
            if (id.startsWith("1")) blocks.add(block("paragraph", "Email: ada@example.test"));
            else { blocks.add(block("heading_2", "Meeting information")); blocks.add(block("paragraph", "Date: 2026-10-10T10:00:00+09:00")); blocks.add(block("paragraph", "Template: default.md")); }
            blocks.add(block("code", marker(id))); return blocks;
        }
        Object block(String type, String text) {
            List<Object> runs = new ArrayList<>();
            if (type.equals("code") && "fragments".equals(defect)) { int mid = text.length() / 2; runs.add(Map.of("plain_text", text.substring(0, mid))); runs.add(Map.of("plain_text", text.substring(mid))); }
            else runs.add(Map.of("plain_text", text, "text", Map.of("content", text)));
            return Map.of("id", UUID.randomUUID().toString(), "type", type, type, Map.of("rich_text", runs));
        }
        String blocks(String id, int start) {
            if (saved.containsKey(id)) {
                List<Object> result = new ArrayList<>(); int index = 0;
                for (var block : JSON.readTree(saved.get(id)).path("children")) {
                    @SuppressWarnings("unchecked") Map<String,Object> mapped = JSON.convertValue(block, Map.class);
                    mapped.put("id", id + "-block-" + index++); result.add(mapped);
                }
                requested.add(id + ":" + start);
                int end = Math.min(start + 100, result.size());
                return children(result.subList(start, end), end < result.size() ? Integer.toString(end) : null);
            }
            return children(ordinary(id), null);
        }
        String page(String id) {
            if ("page".equals(defect) && id.equals("1000")) return "{}";
            String title = id.startsWith("1") ? "Ada" : "Synthetic meeting";
            String storage = id.startsWith("1") ? "<p>Email: ada@example.test</p>" : "<h2>Meeting information</h2><p>Date: 2026-10-10T10:00:00+09:00</p><p>Template: default.md</p>";
            if (saved.containsKey(id)) {
                var data = JSON.readTree(saved.get(id));
                title = notion ? data.path("properties").path("title").path("title").get(0).path("text").path("content").asString() : data.path("title").asString();
                if (!notion) storage = data.path("body").path("value").asString();
                if ("readback".equals(defect)) title = "Changed after create";
            } else storage += "fragments".equals(defect) ? "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body><![CDATA[" + marker(id) + "]]></ac:plain-text-body></ac:structured-macro>"
                    : "<pre>" + marker(id).replace("&", "&amp;").replace("<", "&lt;") + "</pre>";
            return notion ? JSON.writeValueAsString(Map.of("id", id, "last_edited_time", "2026-10-10T01:00:00.000Z", "url", "https://notion.test/" + id,
                    "properties", Map.of("title", Map.of("title", List.of(Map.of("plain_text", title))))))
                    : JSON.writeValueAsString(Map.of("id", id, "title", title, "status", "current", "parentId", "3", "version", Map.of("number", 7), "spaceId", "5", "body", Map.of("storage", Map.of("value", storage))));
        }
    }
}
