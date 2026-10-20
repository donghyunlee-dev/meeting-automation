package com.meetingautomation.minutes.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.document.TranscriptSegment;
import com.meetingautomation.minutes.GroundedMinutesResult;
import com.meetingautomation.minutes.MinutesCommand;
import com.meetingautomation.minutes.MinutesGenerationException;
import com.meetingautomation.minutes.MinutesGenerationSettings;
import com.meetingautomation.minutes.MinutesGenerationProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiMinutesGenerationAdapterTests {
    private static final String URI = "https://api.openai.com/v1/chat/completions";
    private static final String API_KEY = "minutes-api-key-secret";

    @Test
    void sendsSchemaConstrainedRequestAndKeepsCommandToStringRedacted() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(allOf(
                        containsString("\"type\":\"json_schema\""),
                        containsString("\"strict\":true"),
                        containsString("Ada Lovelace"),
                        containsString("The release is scheduled for Friday."),
                        containsString("# Meeting minutes"),
                        not(containsString("ada@example.test")))))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"{\\"templateId\\":\\"default.md\\",\\"templateVersion\\":\\"1.0.0\\",\\"summaryClaims\\":[{\\"text\\":\\"Release is Friday\\",\\"evidenceSegmentIds\\":[\\"seg_001\\"]}],\\"discussionPointClaims\\":[],\\"decisionClaims\\":[],\\"actionItemClaims\\":[],\\"followUpClaims\\":[]}"}}]}
                        """, MediaType.APPLICATION_JSON));

        MinutesCommand command = command();
        GroundedMinutesResult result = new OpenAiMinutesGenerationAdapter(builder,
                new MinutesGenerationSettings("minutes-test-model", API_KEY, 10, 30)).generateMinutes(command);

        assertThat(result.templateId()).isEqualTo("default.md");
        assertThat(result.summaryClaims()).containsExactly(new GroundedMinutesResult.TextClaim(
                "Release is Friday", List.of("seg_001")));
        assertThat(command.toString()).doesNotContain(
                "Ada Lovelace", "The release is scheduled", "# Meeting minutes");
        assertThat(result.toString()).doesNotContain("Release is Friday");
        server.verify();
    }

    @Test
    void classifiesRateLimitAsRetryableWithoutRetainingProviderBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URI)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body("provider-private-response"));
        OpenAiMinutesGenerationAdapter adapter = new OpenAiMinutesGenerationAdapter(builder,
                new MinutesGenerationSettings("minutes-test-model", API_KEY, 10, 30));

        assertThatThrownBy(() -> adapter.generateMinutes(command()))
                .isInstanceOf(MinutesGenerationException.class)
                .satisfies(error -> {
                    MinutesGenerationException failure = (MinutesGenerationException) error;
                    assertThat(failure.code()).isEqualTo(MinutesGenerationException.Code.PROCESSING_FAILED);
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.getMessage()).doesNotContain("provider-private-response", API_KEY);
                });
        server.verify();
    }

    @Test
    void classifiesAuthenticationFailureAsPermanentWithoutRetainingProviderBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("private-auth-provider-response"));
        OpenAiMinutesGenerationAdapter adapter = new OpenAiMinutesGenerationAdapter(builder,
                new MinutesGenerationSettings("minutes-test-model", API_KEY, 10, 30));

        assertThatThrownBy(() -> adapter.generateMinutes(command()))
                .isInstanceOf(MinutesGenerationException.class)
                .satisfies(error -> {
                    MinutesGenerationException failure = (MinutesGenerationException) error;
                    assertThat(failure.code()).isEqualTo(MinutesGenerationException.Code.AUTHENTICATION_FAILED);
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).doesNotContain("private-auth-provider-response", API_KEY);
                });
        server.verify();
    }

    @Test
    void rejectsMalformedStructuredContentWithoutRetainingProviderOutput() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URI)).andRespond(withSuccess("""
                {"choices":[{"finish_reason":"stop","message":{"content":"private malformed transcript output"}}]}
                """, MediaType.APPLICATION_JSON));
        OpenAiMinutesGenerationAdapter adapter = new OpenAiMinutesGenerationAdapter(builder,
                new MinutesGenerationSettings("minutes-test-model", API_KEY, 10, 30));

        assertThatThrownBy(() -> adapter.generateMinutes(command()))
                .isInstanceOf(MinutesGenerationException.class)
                .satisfies(error -> {
                    MinutesGenerationException failure = (MinutesGenerationException) error;
                    assertThat(failure.code()).isEqualTo(MinutesGenerationException.Code.PROCESSING_FAILED);
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).doesNotContain("private malformed transcript output", API_KEY);
                });
        server.verify();
    }

    @Test
    void missingCredentialsFailLocallyWithoutSendingTranscriptToProvider() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiMinutesGenerationAdapter adapter = new OpenAiMinutesGenerationAdapter(builder,
                new MinutesGenerationSettings("", "", 10, 30));

        assertThatThrownBy(() -> adapter.generateMinutes(command()))
                .isInstanceOf(MinutesGenerationException.class)
                .satisfies(error -> {
                    MinutesGenerationException failure = (MinutesGenerationException) error;
                    assertThat(failure.code()).isEqualTo(MinutesGenerationException.Code.CONFIGURATION_ERROR);
                    assertThat(failure.retryable()).isFalse();
                });
        server.verify();
    }

    @Test
    void bindsRuntimeModelAndTimeoutSettingsWhileRedactingApiKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(MinutesGenerationProviderConfiguration.class)
                .withPropertyValues("minutes.model=model-from-environment", "openai.api-key=" + API_KEY,
                        "minutes.connect-timeout-seconds=7", "minutes.timeout-seconds=45")
                .run(context -> {
                    assertThat(context).hasSingleBean(MinutesGenerationProvider.class);
                    MinutesGenerationSettings settings = context.getBean(MinutesGenerationSettings.class);
                    assertThat(settings.model()).isEqualTo("model-from-environment");
                    assertThat(settings.connectTimeoutSeconds()).isEqualTo(7);
                    assertThat(settings.timeoutSeconds()).isEqualTo(45);
                    assertThat(settings.toString()).contains("model-from-environment", "apiKey=<redacted>")
                            .doesNotContain(API_KEY);
                });
    }

    private static MinutesCommand command() {
        return new MinutesCommand("default.md", "1.0.0", "# Meeting minutes",
                List.of(new MeetingSession.ParticipantReference("participant-1", "Ada Lovelace")),
                Map.of(), List.of(new TranscriptSegment("seg_001", "speaker_a", 0, 500,
                        "The release is scheduled for Friday.")));
    }
}
