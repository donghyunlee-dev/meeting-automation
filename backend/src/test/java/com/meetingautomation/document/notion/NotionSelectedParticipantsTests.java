package com.meetingautomation.document.notion;

import com.meetingautomation.document.Participant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NotionSelectedParticipantsTests {
    @Test
    void readsOnlySelectedBodiesFromTheConfiguredRoster() {
        var builder = RestClient.builder().baseUrl("https://api.notion.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new NotionPageHierarchyAdapter(builder, "token", "root");
        server.expect(requestTo("https://api.notion.com/v1/blocks/roster/children"))
                .andRespond(withSuccess("""
                    {"has_more":false,"results":[
                    {"id":"unselected","type":"child_page","child_page":{"title":"Other"}},
                    {"id":"chosen","type":"child_page","child_page":{"title":"Ada"}}]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/blocks/chosen/children"))
                .andRespond(withSuccess("""
                    {"has_more":false,"results":[{"id":"email","type":"paragraph",
                    "paragraph":{"rich_text":[{"plain_text":"Email: ada@example.test"}]}}]}
                    """, MediaType.APPLICATION_JSON));
        assertEquals(List.of(new Participant("chosen", "Ada", "ada@example.test")),
                adapter.findParticipants("roster", List.of("chosen")));
        server.verify();
    }

    @Test
    void emptySelectionDoesNotCallNotion() {
        var builder = RestClient.builder().baseUrl("https://api.notion.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var adapter = new NotionPageHierarchyAdapter(builder, "token", "root");
        assertEquals(List.of(), adapter.findParticipants("roster", List.of()));
        server.verify();
    }
}
