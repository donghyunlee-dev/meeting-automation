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
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Sends assembled audio to OpenAI while keeping provider JSON outside the application contract. */
public final class OpenAiTranscriptionAdapter implements TranscriptionProvider {
    private static final URI TRANSCRIPTION_URI = URI.create("https://api.openai.com/v1/audio/transcriptions");
    private static final String RESPONSE_FORMAT = "diarized_json";
    private static final long CHUNKING_REQUIRED_AFTER_MS = 30_000;

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
        MediaType audioMediaType = validateAudio(command);

        try {
            JsonNode response = restClient.post()
                    .uri(TRANSCRIPTION_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey().strip())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(requestBody(command, audioMediaType))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw new ProviderStatusException(clientResponse.getStatusCode().value());
                    })
                    .body(JsonNode.class);
            return mapResponse(response, command.meetingDurationMs());
        } catch (ProviderStatusException providerFailure) {
            throw mapStatus(providerFailure.statusCode());
        } catch (ResourceAccessException transportFailure) {
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, true);
        } catch (TranscriptionException safeFailure) {
            throw safeFailure;
        } catch (RestClientException malformedResponse) {
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        } catch (RuntimeException malformedResponse) {
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
    }

    private void validateSettings() {
        if (settings.model() == null || settings.model().isBlank()
                || settings.apiKey() == null || settings.apiKey().isBlank() || settings.maxAudioBytes() < 1) {
            throw failure(TranscriptionException.Code.CONFIGURATION_ERROR, false);
        }
    }

    private MediaType validateAudio(TranscriptionCommand command) {
        try {
            MediaType type = MediaType.parseMediaType(command.mimeType());
            Path audio = command.audioReference();
            if (!"audio".equalsIgnoreCase(type.getType()) || fileExtension(type) == null
                    || Files.isSymbolicLink(audio)
                    || !Files.isRegularFile(audio, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(audio)) {
                throw failure(TranscriptionException.Code.INVALID_AUDIO, false);
            }
            long audioBytes = Files.size(audio);
            if (audioBytes < 1 || audioBytes > settings.maxAudioBytes()) {
                throw failure(TranscriptionException.Code.INVALID_AUDIO, false);
            }
            return type;
        } catch (TranscriptionException invalidAudio) {
            throw invalidAudio;
        } catch (IOException | IllegalArgumentException | SecurityException invalidAudio) {
            throw failure(TranscriptionException.Code.INVALID_AUDIO, false);
        }
    }

    private MultiValueMap<String, Object> requestBody(TranscriptionCommand command, MediaType audioMediaType) {
        HttpHeaders audioHeaders = new HttpHeaders();
        audioHeaders.setContentType(audioMediaType);
        HttpEntity<FileSystemResource> audioPart = new HttpEntity<>(
                new FileSystemResource(command.audioReference()) {
                    @Override
                    public String getFilename() {
                        return "assembled." + fileExtension(audioMediaType);
                    }
                }, audioHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", audioPart);
        body.add("model", settings.model().strip());
        body.add("response_format", RESPONSE_FORMAT);
        if (command.meetingDurationMs() > CHUNKING_REQUIRED_AFTER_MS) body.add("chunking_strategy", "auto");
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

    private static ProviderTranscriptionResponse mapResponse(JsonNode response, long meetingDurationMs) {
        if (response == null || !response.isObject() || !response.path("segments").isArray()) {
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
        List<ProviderTranscriptionResponse.Segment> segments = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        for (JsonNode segment : response.path("segments")) {
            if (!segment.isObject() || !segment.path("speaker").isTextual()
                    || !segment.path("text").isTextual() || !segment.path("start").isNumber()
                    || !segment.path("end").isNumber()) {
                throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
            }
            String providerLabel = segment.path("speaker").textValue();
            String providerSegmentId = optionalText(segment, "id");
            long startMs = toMilliseconds(segment.path("start").decimalValue());
            long endMs = toMilliseconds(segment.path("end").decimalValue());
            String text = segment.path("text").textValue();
            labels.add(providerLabel);
            segments.add(new ProviderTranscriptionResponse.Segment(
                    providerSegmentId, providerLabel, startMs, endMs, text));
        }

        List<ProviderTranscriptionResponse.Speaker> speakers = labels.stream()
                .map(ProviderTranscriptionResponse.Speaker::new).toList();
        try {
            ProviderTranscriptionResponse result = new ProviderTranscriptionResponse(speakers, segments);
            result.validateMeetingDuration(meetingDurationMs);
            return result;
        } catch (IllegalArgumentException invalidResponse) {
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        return value.textValue();
    }

    private static long toMilliseconds(BigDecimal seconds) {
        try {
            return seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalidTimestamp) {
            throw failure(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
    }

    private static TranscriptionException mapStatus(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return failure(TranscriptionException.Code.AUTHENTICATION_FAILED, false);
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

        private ProviderStatusException(int statusCode) {
            super("Provider request failed.");
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
