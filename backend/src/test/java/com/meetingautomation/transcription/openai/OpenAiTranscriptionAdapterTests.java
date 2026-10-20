package com.meetingautomation.transcription.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.meetingautomation.transcription.ProviderTranscriptionResponse;
import com.meetingautomation.transcription.TranscriptionCommand;
import com.meetingautomation.transcription.TranscriptionException;
import com.meetingautomation.transcription.TranscriptionProvider;
import com.meetingautomation.transcription.TranscriptionProviderConfiguration;
import com.meetingautomation.transcription.TranscriptionSettings;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiTranscriptionAdapterTests {
    private static final String API_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String AUDIO_SENTINEL = "fixture-audio-sentinel";
    private static final String SECRET_SENTINEL = "test-provider-secret";

    @TempDir
    Path tempDirectory;

    @Test
    void streamsAudioWithConfiguredModelAndMapsDiarizedJsonToProviderNeutralLabels() throws IOException {
        TestClient testClient = client("transcribe-model-v1");
        Path audio = audioFile();
        String providerResponse = """
                {"segments":[
                  {"id":"seg_2","start":1.25,"end":1.5,"text":"second words","speaker":"speaker_b"},
                  {"id":"seg_1","start":0.1,"end":1.0,"text":"first words","speaker":"speaker_a"}
                ]}
                """;
        testClient.server().expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AUTHORIZATION, "Bearer " + SECRET_SENTINEL))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("name=\"model\""),
                        org.hamcrest.Matchers.containsString("transcribe-model-v1"),
                        org.hamcrest.Matchers.containsString("name=\"response_format\""),
                        org.hamcrest.Matchers.containsString("diarized_json"),
                        org.hamcrest.Matchers.containsString("name=\"chunking_strategy\""),
                        org.hamcrest.Matchers.containsString("auto"),
                        org.hamcrest.Matchers.containsString("name=\"language\""),
                        org.hamcrest.Matchers.containsString("ko"),
                        org.hamcrest.Matchers.containsString("filename=\"assembled.webm\""),
                        org.hamcrest.Matchers.containsString(AUDIO_SENTINEL))))
                .andRespond(withSuccess(providerResponse, MediaType.APPLICATION_JSON));

        ProviderTranscriptionResponse response = testClient.adapter().transcribeWithDiarization(
                new TranscriptionCommand(audio, "audio/webm", "ko", 31_000));

        assertEquals(java.util.List.of("speaker_b", "speaker_a"),
                response.speakers().stream().map(ProviderTranscriptionResponse.Speaker::providerLabel).toList());
        assertEquals("seg_2", response.segments().getFirst().providerSegmentId());
        assertEquals("speaker_b", response.segments().getFirst().providerLabel());
        assertEquals(1250, response.segments().getFirst().startMs());
        assertEquals(1500, response.segments().getFirst().endMs());
        assertEquals("second words", response.segments().getFirst().text());
        assertEquals("seg_1", response.segments().get(1).providerSegmentId());
        testClient.server().verify();
    }

    @Test
    void mapsNoSpeechResponseToAnEmptyProviderNeutralResult() throws IOException {
        TestClient testClient = client("configured-model");
        testClient.server().expect(requestTo(API_URL))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("name=\"chunking_strategy\""),
                        org.hamcrest.Matchers.containsString("auto"))))
                .andRespond(withSuccess("{\"segments\":[]}", MediaType.APPLICATION_JSON));

        ProviderTranscriptionResponse response = testClient.adapter().transcribeWithDiarization(
                new TranscriptionCommand(audioFile(), "audio/webm", null, 1500));

        assertEquals(java.util.List.of(), response.speakers());
        assertEquals(java.util.List.of(), response.segments());
        testClient.server().verify();
    }

    @Test
    void rejectsMalformedProviderResponseWithoutIncludingItsBody() throws IOException {
        TestClient testClient = client("configured-model");
        String body = "{\"segments\":[{\"id\":\"private-transcript\",\"start\":0,\"end\":1,\"text\":\"secret transcript\"}]}";
        testClient.server().expect(requestTo(API_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        TranscriptionException failure = assertThrows(TranscriptionException.class,
                () -> testClient.adapter().transcribeWithDiarization(
                        new TranscriptionCommand(audioFile(), "audio/webm", null, 1000)));

        assertEquals(TranscriptionException.Code.PROCESSING_FAILED, failure.code());
        assertFalse(failure.retryable());
        assertFalse(failure.getMessage().contains("private-transcript"));
        assertFalse(failure.getMessage().contains("secret transcript"));
        testClient.server().verify();
    }

    @Test
    void mapsProviderHttpFailuresToSafeRetryableOrPermanentCodes() throws IOException {
        assertProviderFailure(429, TranscriptionException.Code.PROCESSING_FAILED, true);
        assertProviderFailure(500, TranscriptionException.Code.PROCESSING_FAILED, true);
        assertProviderFailure(400, TranscriptionException.Code.INVALID_AUDIO, false);
        assertProviderFailure(413, TranscriptionException.Code.INVALID_AUDIO, false);
        assertProviderFailure(415, TranscriptionException.Code.INVALID_AUDIO, false);
        assertProviderFailure(401, TranscriptionException.Code.AUTHENTICATION_FAILED, false);
        assertProviderFailure(403, TranscriptionException.Code.AUTHENTICATION_FAILED, false);
    }

    @Test
    void mapsUnsupportedDiarizedJsonFormatToSafeConfigurationFailure() throws IOException {
        TestClient testClient = client("non-diarization-model");
        String providerError = """
                {"error":{"type":"invalid_request_error","code":"unsupported_value",
                  "param":"response_format","message":"private provider diagnostic"}}
                """;
        testClient.server().expect(requestTo(API_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(400))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(providerError));

        TranscriptionException failure = assertThrows(TranscriptionException.class,
                () -> testClient.adapter().transcribeWithDiarization(
                        new TranscriptionCommand(audioFile(), "audio/webm", null, 1000)));

        assertEquals(TranscriptionException.Code.CONFIGURATION_ERROR, failure.code());
        assertFalse(failure.retryable());
        assertFalse(failure.getMessage().contains("private provider diagnostic"));
        assertFalse(failure.toString().contains("private provider diagnostic"));
        testClient.server().verify();
    }

    @Test
    void mapsProviderTimeoutToSafeRetryableFailureWithoutRetainingExceptionCause() throws IOException {
        TestClient testClient = client("configured-model");
        testClient.server().expect(requestTo(API_URL))
                .andRespond(withException(new SocketTimeoutException("sensitive-provider-detail")));

        TranscriptionException failure = assertThrows(TranscriptionException.class,
                () -> testClient.adapter().transcribeWithDiarization(
                        new TranscriptionCommand(audioFile(), "audio/webm", null, 1000)));

        assertEquals(TranscriptionException.Code.PROCESSING_FAILED, failure.code());
        assertEquals(true, failure.retryable());
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains("sensitive-provider-detail"));
        testClient.server().verify();
    }

    @Test
    void rejectsMissingConfigurationAndMissingAudioWithoutMakingProviderRequest() throws IOException {
        TestClient missingKeyClient = clientWithSettings(new TranscriptionSettings("model", " "));
        TranscriptionException missingKey = assertThrows(TranscriptionException.class,
                () -> missingKeyClient.adapter().transcribeWithDiarization(
                        new TranscriptionCommand(audioFile(), "audio/webm", null, 1000)));
        assertEquals(TranscriptionException.Code.CONFIGURATION_ERROR, missingKey.code());
        assertFalse(missingKey.retryable());

        TestClient missingAudioClient = client("model");
        TranscriptionException missingAudio = assertThrows(TranscriptionException.class,
                () -> missingAudioClient.adapter().transcribeWithDiarization(
                        new TranscriptionCommand(tempDirectory.resolve("absent.webm"), "audio/webm", null, 1000)));
        assertEquals(TranscriptionException.Code.INVALID_AUDIO, missingAudio.code());
        assertFalse(missingAudio.retryable());
        missingKeyClient.server().verify();
        missingAudioClient.server().verify();
    }

    @Test
    void rejectsAudioLargerThanConfiguredProviderLimitBeforeSending() throws IOException {
        TestClient testClient = clientWithSettings(new TranscriptionSettings("configured-model", SECRET_SENTINEL, 10));

        TranscriptionException failure = assertThrows(TranscriptionException.class,
                () -> testClient.adapter().transcribeWithDiarization(
                        new TranscriptionCommand(audioFile(), "audio/webm", null, 1000)));

        assertEquals(TranscriptionException.Code.INVALID_AUDIO, failure.code());
        assertFalse(failure.retryable());
        testClient.server().verify();
    }

    @Test
    void rejectsUnsupportedAudioFormatBeforeSending() throws IOException {
        TestClient testClient = client("configured-model");

        TranscriptionException failure = assertThrows(TranscriptionException.class,
                () -> testClient.adapter().transcribeWithDiarization(
                        new TranscriptionCommand(audioFile(), "audio/aiff", null, 1000)));

        assertEquals(TranscriptionException.Code.INVALID_AUDIO, failure.code());
        assertFalse(failure.retryable());
        testClient.server().verify();
    }

    @Test
    void omitsOptionalLanguageAndNeverPrintsConfiguredSecret() throws IOException {
        TestClient testClient = client("configured-model");
        testClient.server().expect(requestTo(API_URL))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"language\""))))
                .andRespond(withSuccess("{\"segments\":[]}", MediaType.APPLICATION_JSON));

        testClient.adapter().transcribeWithDiarization(
                new TranscriptionCommand(audioFile(), "audio/webm", null, 1000));

        assertFalse(testClient.settings().toString().contains(SECRET_SENTINEL));
        testClient.server().verify();
    }

    @Test
    void bindsDeploymentModelAndSecretFromBackendConfigurationWithoutExposingSecret() {
        new ApplicationContextRunner()
                .withUserConfiguration(TranscriptionProviderConfiguration.class)
                .withPropertyValues(
                        "transcription.model=deployment-selected-model",
                        "openai.api-key=" + SECRET_SENTINEL,
                        "transcription.max-audio-bytes=24",
                        "transcription.connect-timeout-seconds=3",
                        "transcription.timeout-seconds=1")
                .run(context -> {
                    TranscriptionSettings settings = context.getBean(TranscriptionSettings.class);

                    assertEquals("deployment-selected-model", settings.model());
                    assertEquals(SECRET_SENTINEL, settings.apiKey());
                    assertEquals(24, settings.maxAudioBytes());
                    assertEquals(3, settings.connectTimeoutSeconds());
                    assertEquals(1, settings.timeoutSeconds());
                    assertFalse(settings.toString().contains(SECRET_SENTINEL));
                    assertEquals(OpenAiTranscriptionAdapter.class,
                            context.getBean(TranscriptionProvider.class).getClass());
                });
    }

    private void assertProviderFailure(int status, TranscriptionException.Code code, boolean retryable)
            throws IOException {
        TestClient testClient = client("configured-model");
        String providerBody = status == 400
                ? "{\"error\":{\"param\":\"file\",\"code\":\"invalid_audio\",\"message\":\"provider response body secret\"}}"
                : "provider response body secret";
        testClient.server().expect(requestTo(API_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status))
                        .body(providerBody));

        TranscriptionException failure = assertThrows(TranscriptionException.class,
                () -> testClient.adapter().transcribeWithDiarization(
                        new TranscriptionCommand(audioFile(), "audio/webm", null, 1000)));

        assertEquals(code, failure.code());
        assertEquals(retryable, failure.retryable());
        assertFalse(failure.getMessage().contains("provider response body secret"));
        assertFalse(failure.toString().contains(SECRET_SENTINEL));
        testClient.server().verify();
    }

    private TestClient client(String model) {
        return clientWithSettings(new TranscriptionSettings(model, SECRET_SENTINEL));
    }

    private TestClient clientWithSettings(TranscriptionSettings settings) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(new OpenAiTranscriptionAdapter(builder, settings), server, settings);
    }

    private Path audioFile() throws IOException {
        Path audio = tempDirectory.resolve("audio.bin");
        Files.writeString(audio, AUDIO_SENTINEL, StandardCharsets.UTF_8);
        return audio;
    }

    private record TestClient(OpenAiTranscriptionAdapter adapter, MockRestServiceServer server,
            TranscriptionSettings settings) {
    }
}
