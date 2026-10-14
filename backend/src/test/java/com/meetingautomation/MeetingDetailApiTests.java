package com.meetingautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.document.DocumentStructure;
import com.meetingautomation.document.DocumentStructureProvider;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.MeetingDocumentRef;
import com.meetingautomation.document.Participant;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.ProviderHealth;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.document.transfer.DocumentTransferPort;
import com.meetingautomation.document.transfer.TransferManifest;
import com.meetingautomation.document.transfer.TransferMarker;
import com.meetingautomation.document.transfer.TransferRecord;
import com.meetingautomation.document.settings.DocumentProviderFactory;
import com.meetingautomation.document.settings.GlobalDocumentSettings;
import com.meetingautomation.document.settings.GlobalSettingsStore;
import java.time.LocalDate;
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
class MeetingDetailApiTests {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private GlobalSettingsStore settingsStore;
    @MockitoBean private DocumentProviderFactory providerFactory;

    private final FakeProvider provider = new FakeProvider();

    @BeforeEach
    void configureActiveProvider() {
        provider.reset();
        when(settingsStore.read()).thenReturn(new GlobalDocumentSettings(1, 1,
                new GlobalDocumentSettings.Connection("meeting-detail-test", 1, "NOTION", Map.of(),
                        Map.of("token", "secret-do-not-return"), "root", "meetings", "participants",
                        java.time.Instant.now()), null, null, Map.of()));
        when(providerFactory.create(any())).thenReturn(provider);
    }

    @Test
    void detailReturnsNormalizedMeetingWithOffsetRosterMinutesTranscriptAndSafeUrl() throws Exception {
        provider.records = List.of(meeting("doc_detail", "https://docs.example/meeting", "private summary"));
        String body = mockMvc.perform(get("/api/v1/meetings/doc_detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value("doc_detail"))
                .andExpect(jsonPath("$.data.title").value("Planning"))
                .andExpect(jsonPath("$.data.meetingAt").value("2026-10-04T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.participants.length()").value(1))
                .andExpect(jsonPath("$.data.participants[0].id").value("pt_1"))
                .andExpect(jsonPath("$.data.participants[0].name").value("Ada"))
                .andExpect(jsonPath("$.data.minutes.templateId").value("default.md"))
                .andExpect(jsonPath("$.data.minutes.templateVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.minutes.summary").value("private summary"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].ownerParticipantId").value("pt_1"))
                .andExpect(jsonPath("$.data.minutes.actionItems[0].dueDate").value("2026-10-08"))
                .andExpect(jsonPath("$.data.transcript[0].segmentId").value("seg_1"))
                .andExpect(jsonPath("$.data.transcript[0].speakerId").value("speaker_a"))
                .andExpect(jsonPath("$.data.transcript[0].startMs").value(0))
                .andExpect(jsonPath("$.data.transcript[0].endMs").value(1250))
                .andExpect(jsonPath("$.data.transcript[0].text").value("hello"))
                .andExpect(jsonPath("$.data.documentUrl").value("https://docs.example/meeting"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("ada@example.test", "secret-do-not-return", "provider raw body");
        assertThat(provider.listCalls).isEqualTo(1);
        assertThat(provider.exportCalls).isZero();
        assertThat(provider.mutations).isZero();
    }

    @Test
    void detailOnlyReturnsExactMembersOfConfiguredMeetingsCollection() throws Exception {
        provider.records = List.of(meeting("doc_in_collection", "https://docs.example/valid", "valid content"));
        provider.exportRecord = meeting("doc_outside", "https://docs.example/outside", "outside private content");

        String body = mockMvc.perform(get("/api/v1/meetings/doc_outside"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("outside private content", "https://docs.example/outside");
        assertThat(provider.listCalls).isEqualTo(1);
        assertThat(provider.exportCalls).isZero();
    }

    @Test
    void missingMeetingsCollectionStructureRemains422() throws Exception {
        provider.missingStructure = true;

        mockMvc.perform(get("/api/v1/meetings/doc_detail"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_STRUCTURE_NOT_FOUND"));
        assertThat(provider.exportCalls).isZero();
    }

    @Test
    void missingMeetingMapsTo404AndProviderFailureToSanitized502() throws Exception {
        mockMvc.perform(get("/api/v1/meetings/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOT_FOUND"));
        provider.fail = true;
        String body = mockMvc.perform(get("/api/v1/meetings/doc_detail"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("token-secret-marker", "provider raw body");
    }

    @Test
    void invalidMinutesOrTranscriptAndUnsafeDocumentUrlNeverReturnPartialOrUnsafeData() throws Exception {
        TransferRecord valid = provider.records.getFirst();
        provider.records = List.of(new TransferRecord("doc_detail", "r1", "javascript:alert(1)",
                valid.marker(), "Planning", null, valid.transcript(), valid.participants(), null));
        mockMvc.perform(get("/api/v1/meetings/doc_detail"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"));

        provider.records = List.of(new TransferRecord("doc_detail", "r1", null, valid.marker(), "Planning", valid.minutes(),
                List.of(new TranscriptSegment("seg_1", "speaker_a", 10, 5, "invalid timing")), valid.participants(), null));
        mockMvc.perform(get("/api/v1/meetings/doc_detail"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_FAILED"));

        provider.records = List.of(meeting("doc_detail", "javascript:alert(1)", "summary"));
        mockMvc.perform(get("/api/v1/meetings/doc_detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    private static TransferRecord meeting(String id, String url, String summary) {
        var marker = new TransferMarker("meeting-automation", "meeting", "1.0", "session-" + id,
                OffsetDateTime.parse("2026-10-04T10:00:00+09:00"), "default.md", List.of("pt_1"), List.of(), null,
                "digest", null);
        return new TransferRecord(id, "r1", url, marker, "Planning",
                new StructuredMinutes("default.md", "1.0.0", summary, List.of("discussion"), List.of("decision"),
                        List.of(new StructuredMinutes.ActionItem("pt_1", "follow up", LocalDate.parse("2026-10-08"))),
                        List.of("next week")),
                List.of(new TranscriptSegment("seg_1", "speaker_a", 0, 1250, "hello")),
                List.of(new ParticipantSummary("pt_1", "Ada")), null);
    }

    private static final class FakeProvider implements DocumentStructureProvider, DocumentTransferPort {
        private List<TransferRecord> records = List.of(meeting("doc_detail", "https://docs.example/meeting", "private summary"));
        private TransferRecord exportRecord;
        private boolean fail;
        private boolean missingStructure;
        private int listCalls;
        private int exportCalls;
        private int mutations;
        void reset() { records = List.of(meeting("doc_detail", "https://docs.example/meeting", "private summary")); exportRecord = null; fail = false; missingStructure = false; listCalls = 0; exportCalls = 0; mutations = 0; }
        @Override public ProviderHealth validateConnection() { return null; }
        @Override public DocumentStructure discoverStructure(String rootId) { return null; }
        @Override public TransferManifest readManifest() { return null; }
        @Override public List<TransferRecord> listMeetingRecords() {
            listCalls++;
            if (missingStructure) throw DocumentProviderException.structureNotFound();
            if (fail) throw DocumentProviderException.documentFailed(true,
                    new IllegalStateException("token-secret-marker provider raw body"));
            return records;
        }
        @Override public TransferRecord exportMeeting(String id) {
            exportCalls++;
            if (fail) throw DocumentProviderException.documentFailed(true, new IllegalStateException("token-secret-marker provider raw body"));
            return exportRecord == null ? records.stream().filter(record -> record.sourceDocumentId().equals(id)).findFirst().orElse(null) : exportRecord;
        }
        @Override public Participant importParticipant(TransferRecord source, String fingerprint) { mutations++; return null; }
        @Override public MeetingDocumentRef importMeeting(TransferRecord source, Map<String, String> map, String fingerprint) { mutations++; return null; }
        @Override public MeetingDocumentRef publishMeeting(TransferRecord source) { mutations++; return null; }
    }
}
