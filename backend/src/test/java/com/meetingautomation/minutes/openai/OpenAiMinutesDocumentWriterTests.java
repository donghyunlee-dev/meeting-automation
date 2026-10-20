package com.meetingautomation.minutes.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.meetingautomation.minutes.MinutesDocumentCommand;
import com.meetingautomation.minutes.MinutesGenerationSettings;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiMinutesDocumentWriterTests {
    private static final String URI = "https://api.openai.com/v1/chat/completions";

    @Test
    void sendsSelectedTemplateAndReviewedMinutesAsTheOnlyMeetingInputs() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer writer-test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("# Custom minutes template"),
                        org.hamcrest.Matchers.containsString("Reviewed decision: launch on Friday"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("speaker_7")),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("transcript-secret")))))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"{\\"markdown\\":\\"# Launch review\\\\n\\\\n## Decisions\\\\n- Launch on Friday\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));

        String markdown = new OpenAiMinutesDocumentWriter(builder,
                new MinutesGenerationSettings("minutes-test-model", "writer-test-key"))
                .render(new MinutesDocumentCommand("# Custom minutes template", "Reviewed decision: launch on Friday"));

        assertThat(markdown).isEqualTo("# Launch review\n\n## Decisions\n- Launch on Friday");
        server.verify();
    }
}
