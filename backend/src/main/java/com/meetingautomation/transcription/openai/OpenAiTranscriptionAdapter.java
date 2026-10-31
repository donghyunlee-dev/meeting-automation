package com.meetingautomation.transcription.openai;

import com.meetingautomation.transcription.ProviderTranscriptionResponse;
import com.meetingautomation.transcription.TranscriptionCommand;
import com.meetingautomation.transcription.TranscriptionException;
import com.meetingautomation.transcription.TranscriptionProvider;
import com.meetingautomation.transcription.TranscriptionSettings;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sends assembled audio to OpenAI while keeping provider JSON outside the application contract. */
public final class OpenAiTranscriptionAdapter implements TranscriptionProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiTranscriptionAdapter.class);
    private static final URI TRANSCRIPTION_URI = URI.create("https://api.openai.com/v1/audio/transcriptions");
    private static final String RESPONSE_FORMAT = "diarized_json";
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final TranscriptionSettings settings;

    public OpenAiTranscriptionAdapter(RestClient.Builder builder, TranscriptionSettings settings) {
        if (builder == null || settings == null) {
            throw new IllegalArgumentException("Transcription adapter configuration is invalid.");
        }
        this.restClient = builder.build();
        this.settings = settings;
    }

    @Override
    public ProviderTranscriptionResponse transcribeWithDiarization(TranscriptionCommand command) {
        if (command == null) throw failure(TranscriptionException.Code.INVALID_AUDIO, false);
        validateSettings();
        ValidatedAudio audio = validateAudio(command);
        long startedAt = System.nanoTime();
        LOGGER.info("Transcription provider request started traceId={} audioBytes={} meetingDurationMs={} mimeType={}",
                command.traceId(), audio.bytes(), command.meetingDurationMs(), audio.mediaType());

        try {
            JsonNode response = restClient.post()
                    .uri(TRANSCRIPTION_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey().strip())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(requestBody(command, audio.mediaType()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        int statusCode = clientResponse.getStatusCode().value();
                        throw new ProviderStatusException(statusCode,
                                statusCode == 400 && isResponseFormatError(clientResponse));
                    })
                    .body(JsonNode.class);
            ProviderTranscriptionResponse result = mapResponse(response, command, startedAt, audio.bytes());
            long maxSegmentEndMs = result.segments().stream()
                    .mapToLong(ProviderTranscriptionResponse.Segment::endMs).max().orElse(-1);
            LOGGER.info("Transcription provider request succeeded traceId={} audioBytes={} meetingDurationMs={} segmentCount={} maxSegmentEndMs={} elapsedMs={}",
                    command.traceId(), audio.bytes(), command.meetingDurationMs(), result.segments().size(), maxSegmentEndMs,
                    elapsedMs(startedAt));
            return result;
        } catch (ProviderStatusException providerFailure) {
            TranscriptionException mapped = mapStatus(providerFailure.statusCode(), providerFailure.responseFormatRejected());
            LOGGER.warn("Transcription provider rejected request traceId={} audioBytes={} meetingDurationMs={} providerStatus={} errorCode={} retryable={} elapsedMs={}",
                    command.traceId(), audio.bytes(), command.meetingDurationMs(), providerFailure.statusCode(),
                    mapped.code(), mapped.retryable(), elapsedMs(startedAt));
            throw mapped;
        } catch (ResourceAccessException transportFailure) {
            LOGGER.warn("Transcription provider transport failed traceId={} audioBytes={} meetingDurationMs={} failureType={} errorCode={} retryable=true elapsedMs={}",
                    command.traceId(), audio.bytes(), command.meetingDurationMs(),
                    transportFailure.getMostSpecificCause().getClass().getSimpleName(),
                    TranscriptionException.Code.PROCESSING_FAILED, elapsedMs(startedAt));
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, true);
        } catch (TranscriptionException safeFailure) {
            throw safeFailure;
        } catch (RestClientException malformedResponse) {
            LOGGER.warn("Transcription provider response failed traceId={} audioBytes={} meetingDurationMs={} failureType=MALFORMED_RESPONSE retryable=false elapsedMs={}",
                    command.traceId(), audio.bytes(), command.meetingDurationMs(), elapsedMs(startedAt));
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        } catch (RuntimeException malformedResponse) {
            LOGGER.warn("Transcription provider processing failed traceId={} audioBytes={} meetingDurationMs={} failureType={} retryable=false elapsedMs={}",
                    command.traceId(), audio.bytes(), command.meetingDurationMs(),
                    malformedResponse.getClass().getSimpleName(), elapsedMs(startedAt));
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
    }

    private void validateSettings() {
        if (settings.model() == null || settings.model().isBlank()
                || settings.apiKey() == null || settings.apiKey().isBlank() || settings.maxAudioBytes() < 1
                || settings.connectTimeoutSeconds() < 1 || settings.timeoutSeconds() < 1) {
            throw failure(TranscriptionException.Code.CONFIGURATION_ERROR, false);
        }
    }

    private ValidatedAudio validateAudio(TranscriptionCommand command) {
        try {
            MediaType type = MediaType.parseMediaType(command.mimeType());
            Path audio = command.audioReference();
            if (!"audio".equalsIgnoreCase(type.getType()) || fileExtension(type) == null) {
                throw failure(TranscriptionException.Code.INVALID_AUDIO, false);
            }
            long audioBytes;
            if (command.audioStream() != null) {
                audioBytes = command.audioBytes();
            } else {
                if (audio == null || Files.isSymbolicLink(audio)
                        || !Files.isRegularFile(audio, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(audio)) {
                    throw failure(TranscriptionException.Code.INVALID_AUDIO, false);
                }
                audioBytes = Files.size(audio);
            }
            if (audioBytes < 1 || audioBytes > settings.maxAudioBytes()) {
                throw failure(TranscriptionException.Code.INVALID_AUDIO, false);
            }
            return new ValidatedAudio(type, audioBytes);
        } catch (TranscriptionException invalidAudio) {
            throw invalidAudio;
        } catch (IOException | IllegalArgumentException | SecurityException invalidAudio) {
            throw failure(TranscriptionException.Code.INVALID_AUDIO, false);
        }
    }

    private MultiValueMap<String, Object> requestBody(TranscriptionCommand command, MediaType audioMediaType) {
        HttpHeaders audioHeaders = new HttpHeaders();
        audioHeaders.setContentType(audioMediaType);
        Resource audioResource = command.audioStream() != null
                ? new InputStreamResource(command.audioStream()) {
                    @Override public String getFilename() { return "assembled." + fileExtension(audioMediaType); }
                    @Override public long contentLength() { return command.audioBytes(); }
                }
                : new FileSystemResource(command.audioReference()) {
                    @Override public String getFilename() { return "assembled." + fileExtension(audioMediaType); }
                };
        HttpEntity<Resource> audioPart = new HttpEntity<>(audioResource, audioHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", audioPart);
        body.add("model", settings.model().strip());
        body.add("response_format", RESPONSE_FORMAT);
        body.add("chunking_strategy", "auto");
        if (command.languageHint() != null) body.add("language", command.languageHint());
        return body;
    }

    private static String fileExtension(MediaType audioMediaType) {
        return switch (audioMediaType.getSubtype().toLowerCase(Locale.ROOT)) {
            case "flac" -> "flac";
            case "mpeg", "mp3" -> "mp3";
            case "mpga" -> "mpga";
            case "mp4" -> "mp4";
            case "m4a" -> "m4a";
            case "ogg" -> "ogg";
            case "wav", "x-wav" -> "wav";
            case "webm" -> "webm";
            default -> null;
        };
    }

    private static ProviderTranscriptionResponse mapResponse(JsonNode response, TranscriptionCommand command,
            long startedAt, long audioBytes) {
        if (response == null || !response.isObject() || !response.path("segments").isArray()) {
            throw invalidResponse(command, startedAt, audioBytes, "RESPONSE_SEGMENTS_MISSING_OR_INVALID", 0, -1);
        }
        List<ProviderTranscriptionResponse.Segment> segments = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        long maxSegmentEndMs = -1;
        for (JsonNode segment : response.path("segments")) {
            if (!segment.isObject() || !segment.path("speaker").isTextual()
                    || !segment.path("text").isTextual() || !segment.path("start").isNumber()
                    || !segment.path("end").isNumber()) {
                throw invalidResponse(command, startedAt, audioBytes, "SEGMENT_FIELDS_INVALID",
                        response.path("segments").size(), maxSegmentEndMs);
            }
            String providerLabel = segment.path("speaker").textValue();
            JsonNode id = segment.get("id");
            if (id != null && !id.isNull() && !id.isTextual()) {
                throw invalidResponse(command, startedAt, audioBytes, "SEGMENT_ID_INVALID",
                        response.path("segments").size(), maxSegmentEndMs);
            }
            String providerSegmentId = id == null || id.isNull() ? null : id.textValue();
            long startMs;
            long endMs;
            try {
                startMs = toMilliseconds(segment.path("start").decimalValue());
                endMs = toMilliseconds(segment.path("end").decimalValue());
            } catch (TranscriptionException invalidTimestamp) {
                throw invalidResponse(command, startedAt, audioBytes, "SEGMENT_TIMESTAMP_INVALID",
                        response.path("segments").size(), maxSegmentEndMs);
            }
            String text = segment.path("text").textValue();
            if (providerLabel.isBlank() || text.isBlank()) {
                throw invalidResponse(command, startedAt, audioBytes,
                        providerLabel.isBlank() ? "SPEAKER_LABEL_INVALID" : "SEGMENT_TEXT_EMPTY",
                        response.path("segments").size(), Math.max(maxSegmentEndMs, endMs));
            }
            if (startMs < 0 || endMs < startMs) {
                throw invalidResponse(command, startedAt, audioBytes, "SEGMENT_TIMESTAMPS_INCONSISTENT",
                        response.path("segments").size(), Math.max(maxSegmentEndMs, endMs));
            }
            maxSegmentEndMs = Math.max(maxSegmentEndMs, endMs);
            labels.add(providerLabel);
            segments.add(new ProviderTranscriptionResponse.Segment(
                    providerSegmentId, providerLabel, startMs, endMs, text));
        }

        List<ProviderTranscriptionResponse.Speaker> speakers = labels.stream()
                .map(ProviderTranscriptionResponse.Speaker::new).toList();
        try {
            return new ProviderTranscriptionResponse(speakers, segments);
        } catch (IllegalArgumentException invalidResponse) {
            LOGGER.warn("Transcription provider response rejected traceId={} audioBytes={} meetingDurationMs={} failureType=RESPONSE_CONSISTENCY_INVALID segmentCount={} maxSegmentEndMs={} elapsedMs={}",
                    command.traceId(), audioBytes, command.meetingDurationMs(), segments.size(), maxSegmentEndMs,
                    elapsedMs(startedAt));
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
    }

    private static TranscriptionException invalidResponse(TranscriptionCommand command, long startedAt,
            long audioBytes, String failureType, int segmentCount, long maxSegmentEndMs) {
        long overrunMs = maxSegmentEndMs < 0 ? 0 : Math.max(0, maxSegmentEndMs - command.meetingDurationMs());
        LOGGER.warn("Transcription provider response rejected traceId={} audioBytes={} meetingDurationMs={} failureType={} segmentCount={} maxSegmentEndMs={} overrunMs={} retryable=false elapsedMs={}",
                command.traceId(), audioBytes, command.meetingDurationMs(), failureType, segmentCount,
                maxSegmentEndMs, overrunMs, elapsedMs(startedAt));
        return failure(TranscriptionException.Code.PROCESSING_FAILED, false);
    }

    private static long elapsedMs(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record ValidatedAudio(MediaType mediaType, long bytes) { }

    private static long toMilliseconds(BigDecimal seconds) {
        try {
            return seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalidTimestamp) {
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
    }

    private static boolean isResponseFormatError(ClientHttpResponse response) {
        try {
            JsonNode errorParameter = JSON_MAPPER.readTree(response.getBody()).path("error").path("param");
            return errorParameter.isTextual() && "response_format".equals(errorParameter.textValue());
        } catch (IOException | RuntimeException unreadableErrorBody) {
            return false;
        }
    }

    private static TranscriptionException mapStatus(int statusCode, boolean responseFormatRejected) {
        if (statusCode == 401 || statusCode == 403) {
            return failure(TranscriptionException.Code.AUTHENTICATION_FAILED, false);
        }
        if (statusCode == 400 && responseFormatRejected) {
            return failure(TranscriptionException.Code.CONFIGURATION_ERROR, false);
        }
        if (statusCode == 400 || statusCode == 413 || statusCode == 415) {
            return failure(TranscriptionException.Code.INVALID_AUDIO, false);
        }
        if (statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500) {
            return failure(TranscriptionException.Code.PROCESSING_FAILED, true);
        }
        return failure(TranscriptionException.Code.CONFIGURATION_ERROR, false);
    }

    private static TranscriptionException failure(TranscriptionException.Code code, boolean retryable) {
        return new TranscriptionException(code, retryable);
    }

    private static final class ProviderStatusException extends RuntimeException {
        private final int statusCode;
        private final boolean responseFormatRejected;

        private ProviderStatusException(int statusCode, boolean responseFormatRejected) {
            super("Provider request failed.");
            this.statusCode = statusCode;
            this.responseFormatRejected = responseFormatRejected;
        }

        private int statusCode() {
            return statusCode;
        }

        private boolean responseFormatRejected() {
            return responseFormatRejected;
        }
    }
}
