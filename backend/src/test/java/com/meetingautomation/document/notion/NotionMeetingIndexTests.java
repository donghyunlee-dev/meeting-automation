package com.meetingautomation.document.notion;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingautomation.document.MeetingSummary;
import com.meetingautomation.document.ParticipantSummary;
import com.meetingautomation.document.StructuredMinutes;
import com.meetingautomation.document.transfer.TransferMarker;
import com.meetingautomation.document.transfer.TransferRecord;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotionMeetingIndexTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void rowRoundTripsMeetingLookupAndSummaryFields() throws Exception {
        TransferRecord meeting = new TransferRecord("notion-page-id", "revision", "https://notion.example/page",
                new TransferMarker("meeting-automation", "meeting", "1.0", "session-42",
                        OffsetDateTime.parse("2026-10-22T09:30:00+09:00"), "default.md", List.of("p1"),
                        List.of(), null, "digest", null), "Weekly review",
                new StructuredMinutes("default.md", "1", "summary", List.of(), List.of(), List.of(), List.of()),
                List.of(), List.of(new ParticipantSummary("p1", "Ada Lovelace")), null);

        var entry = NotionMeetingIndex.readRow(json.valueToTree(NotionMeetingIndex.rowBlock(meeting)));

        assertThat(entry.sessionId()).isEqualTo("session-42");
        assertThat(entry.summary()).isEqualTo(new MeetingSummary("notion-page-id", "Weekly review",
                OffsetDateTime.parse("2026-10-22T09:30:00+09:00"),
                List.of(new ParticipantSummary("p1", "Ada Lovelace")), "https://notion.example/page"));
    }

    @Test
    void tableStartsWithAHeaderRowMatchingTheIndexedFields() {
        var table = json.valueToTree(NotionMeetingIndex.tableBlock());

        assertThat(table.path("type").asText()).isEqualTo("table");
        assertThat(table.path("table").path("table_width").asInt()).isEqualTo(6);
        assertThat(table.path("children").get(0).path("table_row").path("cells")).hasSize(6);
    }
}
