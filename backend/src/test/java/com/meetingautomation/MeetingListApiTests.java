package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ProviderHealth;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.transfer.DocumentTransferPort;
import com.meetingautomation.document.transfer.TransferManifest;
import com.meetingautomation.document.transfer.TransferMarker;
import com.meetingautomation.document.transfer.TransferRecord;
import com.meetingautomation.document.settings.DocumentProviderFactory;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"DOCUMENT_PROVIDER=", "NOTIFICATION_PROVIDER=SLACK"})
@AutoConfigureMockMvc
@Import({ErrorContractProbeController.class, ConstraintViolationProbeService.class})
class MeetingListApiTests {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private GlobalSettingsStore settingsStore;
    @MockitoBean private DocumentProviderFactory providerFactory;

    private final FakeProvider provider = new FakeProvider();

    @BeforeEach
    void configureActiveProvider() {
        provider.reset();
        when(settingsStore.read()).thenReturn(new GlobalDocumentSettings(1, 1,
                new GlobalDocumentSettings.Connection("meeting-list-test", 1, "NOTION", Map.of(),
                        Map.of("token", "secret-do-not-return"), "root", "meetings", "participants",
                        java.time.Instant.now()), null, null, Map.of()));
        when(providerFactory.create(any())).thenReturn(provider);
    }

    @Test
    void listUsesNewestRealMeetingTimeOrdersRosterAndAppliesLimitAfterCompleteRead() throws Exception {
        provider.records = List.of(
                meeting("doc-old", "2026-10-01T09:00:00+09:00", List.of("p2", "p1"),
                        List.of(new com.meetingautomation.document.ParticipantSummary("p1", "Ada"),
                                new com.meetingautomation.document.ParticipantSummary("p2", "Grace")),
                        "https://docs.example/old"),
                meeting("doc-new", "2026-10-02T09:00:00+09:00", List.of("p1"),
                        List.of(new com.meetingautomation.document.ParticipantSummary("p1", "Ada")),
                        "javascript:alert(1)"),
                meeting("doc-mid", "2026-10-01T23:30:00+09:00", List.of("p2"),
                        List.of(new com.meetingautomation.document.ParticipantSummary("p2", "Grace")), null));
        provider.records = new java.util.ArrayList<>(provider.records);
        provider.records.add(meeting("doc-mid", "2026-10-01T23:30:00+09:00", List.of("p2"),
                List.of(new com.meetingautomation.document.ParticipantSummary("p2", "Grace")), null));

        mockMvc.perform(get("/api/v1/meetings").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].documentId").value("doc-new"))
                .andExpect(jsonPath("$.data.items[0].participants[0].id").value("p1"))
                .andExpect(jsonPath("$.data.items[0].participants[0].name").value("Ada"))
                .andExpect(jsonPath("$.data.items[0].documentUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[1].documentId").value("doc-mid"))
                .andExpect(jsonPath("$.data.items[1].documentUrl").value(org.hamcrest.Matchers.nullValue()));
        assertThat(provider.listMeetingCalls).isEqualTo(1);
        assertThat(provider.readManifestCalls).isZero();
    }

    @Test
    void missingLimitDefaultsToOneHundredAndEmptyManifestReturnsEmptyItems() throws Exception {
        provider.records = new java.util.ArrayList<>();
        for (int index = 0; index < 105; index++) {
            provider.records.add(meeting("doc-" + index, OffsetDateTime.parse("2026-10-01T00:00:00Z")
                            .plusMinutes(index).toString(), List.of("p1"),
                    List.of(new com.meetingautomation.document.ParticipantSummary("p1", "Ada")), null));
        }
        mockMvc.perform(get("/api/v1/meetings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(100));
        mockMvc.perform(get("/api/v1/meetings").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mockMvc.perform(get("/api/v1/meetings").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(100));
    }

    @Test
    void emptyManifestReturnsEmptySuccessfulItems() throws Exception {
        provider.records = List.of();
        mockMvc.perform(get("/api/v1/meetings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void invalidLimitAndRequiredPageFailureReturnStandardErrorsWithoutPartialItems() throws Exception {
        mockMvc.perform(get("/api/v1/meetings").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/meetings").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/meetings").param("limit", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        provider.failManifest = true;
        mockMvc.perform(get("/api/v1/meetings"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"));
    }

    @Test
    void incompleteRequiredMeetingMetadataFailsTheWholeList() throws Exception {
        provider.records = List.of(
                meeting("doc-good", "2026-10-02T09:00:00+09:00", List.of("p1"),
                        List.of(new com.meetingautomation.document.ParticipantSummary("p1", "Ada")), null),
                meeting("doc-missing-roster", "2026-10-01T09:00:00+09:00", List.of("missing"),
                        List.of(new com.meetingautomation.document.ParticipantSummary("p1", "Ada")), null));
        mockMvc.perform(get("/api/v1/meetings"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"));
    }

    @Test
    void responseNeverContainsParticipantEmailOrMeetingBody() throws Exception {
        provider.records = List.of(meeting("doc-private", "2026-10-02T09:00:00+09:00", List.of("p1"),
                List.of(new com.meetingautomation.document.ParticipantSummary("p1", "Ada")),
                "https://docs.example/private"));
        String body = mockMvc.perform(get("/api/v1/meetings"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret-do-not-return", "ada@example.com", "private meeting body");
    }

    private static TransferRecord meeting(String id, String meetingAt, List<String> participantIds,
            List<com.meetingautomation.document.ParticipantSummary> participants, String url) {
        var marker = new TransferMarker("meeting-automation", "meeting", "1.0", "session-" + id,
                OffsetDateTime.parse(meetingAt), "default.md", participantIds, List.of(), null, "digest", null);
        return new TransferRecord(id, "r1", url, marker, "Planning", new StructuredMinutes("default.md", "1.0",
                "private meeting body", List.of(), List.of(), List.of(), List.of()), null, participants, null);
    }

    private static final class FakeProvider implements DocumentStructureProvider, DocumentTransferPort {
        private List<TransferRecord> records = List.of();
        private boolean failManifest;
        private int readManifestCalls;
        private int listMeetingCalls;
        void reset() { records = List.of(); failManifest = false; readManifestCalls = 0; listMeetingCalls = 0; }
        @Override public ProviderHealth validateConnection() { return null; }
        @Override public DocumentStructure discoverStructure(String rootId) { return null; }
        @Override public TransferManifest readManifest() {
            readManifestCalls++;
            if (failManifest) throw com.meetingautomation.document.DocumentProviderException.documentFailed(true);
            return new TransferManifest("fingerprint", List.of(), records.stream().map(record ->
                    new TransferManifest.Entry(record.sourceDocumentId(), record.sourceRevision(), "digest", "key",
                            record.marker().externalSessionId(), record.marker().participantIds())).toList());
        }
        @Override public List<TransferRecord> listMeetingRecords() {
            listMeetingCalls++;
            if (failManifest) throw com.meetingautomation.document.DocumentProviderException.documentFailed(true);
            return records.stream().collect(java.util.stream.Collectors.toMap(TransferRecord::sourceDocumentId,
                    record -> record, (first, duplicate) -> first, java.util.LinkedHashMap::new)).values().stream().toList();
        }
        @Override public TransferRecord exportMeeting(String sourceDocumentId) {
            return records.stream().filter(record -> record.sourceDocumentId().equals(sourceDocumentId)).findFirst().orElse(null);
        }
        @Override public Participant importParticipant(TransferRecord record, String sourceConnectionFingerprint) { return null; }
        @Override public MeetingDocumentRef importMeeting(TransferRecord record, Map<String, String> map,
                String sourceConnectionFingerprint) { return null; }
        @Override public MeetingDocumentRef publishMeeting(TransferRecord record) { return null; }
    }
}
