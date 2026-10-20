package com.meetingautomation.minutes.openai;

import com.meetingautomation.minutes.MinutesDocumentCommand;
import com.meetingautomation.minutes.MinutesDocumentWriter;
import com.meetingautomation.minutes.MinutesGenerationException;
import com.meetingautomation.minutes.MinutesGenerationSettings;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Creates the final user-facing document after review, following the selected template. */
public final class OpenAiMinutesDocumentWriter implements MinutesDocumentWriter {
    private static final URI COMPLETIONS_URI = URI.create("https://api.openai.com/v1/chat/completions");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient client;
    private final MinutesGenerationSettings settings;

    public OpenAiMinutesDocumentWriter(RestClient.Builder builder, MinutesGenerationSettings settings) {
        if (builder == null || settings == null) throw new IllegalArgumentException("Minutes writer configuration is invalid.");
        client = builder.build(); this.settings = settings;
    }

    @Override public String render(MinutesDocumentCommand command) {
        if (command == null || settings.model() == null || settings.model().isBlank()
                || settings.apiKey() == null || settings.apiKey().isBlank()) throw failure(false);
        try {
            JsonNode response = client.post().uri(COMPLETIONS_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey().strip())
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(request(command)).retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw new ProviderStatusException(clientResponse.getStatusCode().value());
                    }).body(JsonNode.class);
            JsonNode choices = response == null ? null : response.get("choices");
            if (choices == null || !choices.isArray() || choices.size() != 1) throw failure(false);
            JsonNode choice = choices.get(0), message = choice.path("message");
            if (!"stop".equals(choice.path("finish_reason").textValue()) || !message.path("content").isTextual())
                throw failure(false);
            JsonNode content = JSON.readTree(message.path("content").textValue());
            if (content == null || !content.isObject() || content.size() != 1 || !content.path("markdown").isTextual()
                    || content.path("markdown").asString().isBlank()) throw failure(false);
            return content.path("markdown").asString().strip();
        } catch (ProviderStatusException providerFailure) {
            int status = providerFailure.status;
            boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
            var code = status == 401 || status == 403 ? MinutesGenerationException.Code.AUTHENTICATION_FAILED
                    : retryable ? MinutesGenerationException.Code.PROCESSING_FAILED
                    : MinutesGenerationException.Code.CONFIGURATION_ERROR;
            throw new MinutesGenerationException(code, retryable);
        } catch (ResourceAccessException transportFailure) {
            throw new MinutesGenerationException(MinutesGenerationException.Code.PROCESSING_FAILED, true);
        } catch (MinutesGenerationException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException malformedResponse) {
            throw failure(false);
        }
    }

    private Object request(MinutesDocumentCommand command) {
        return JSON.createObjectNode().put("model", settings.model().strip()).put("store", false)
                .set("messages", JSON.createArrayNode()
                        .add(JSON.createObjectNode().put("role", "developer").put("content",
                                "Create the final meeting minutes page from exactly two inputs: selectedTemplate and reviewedMinutes. "
                                + "Follow the template headings, section order, and writing guidance. Omit AI instructions and placeholder examples. "
                                + "The reviewed minutes are authoritative and already reviewed by the user: preserve their facts and wording, only reorganize and polish for the template. "
                                + "The reviewedMinutes input begins with verified meeting information. Copy its topic, date and time, and attendee names into the matching template fields exactly. "
                                + "Use speaker mappings to resolve participant names when organizing reviewed content. Do not output technical IDs, code, a transcript, a speaker-by-speaker script, or unsupported facts. "
                                + "Write concise Korean minutes. Return Markdown in the markdown field."))
                        .add(JSON.createObjectNode().put("role", "user").put("content", JSON.createObjectNode()
                                .put("selectedTemplate", command.templateContent())
                                .put("reviewedMinutes", command.reviewedMinutes()).toString())))
                .set("response_format", JSON.createObjectNode().put("type", "json_schema")
                        .set("json_schema", JSON.createObjectNode().put("name", "meeting_minutes_document")
                                .put("strict", true).set("schema", JSON.createObjectNode()
                                        .put("type", "object").put("additionalProperties", false)
                                        .set("required", JSON.createArrayNode().add("markdown"))
                                        .set("properties", JSON.createObjectNode().set("markdown",
                                                JSON.createObjectNode().put("type", "string"))))));
    }

    private static MinutesGenerationException failure(boolean retryable) {
        return new MinutesGenerationException(MinutesGenerationException.Code.PROCESSING_FAILED, retryable);
    }
    private static final class ProviderStatusException extends RuntimeException {
        private final int status;
        private ProviderStatusException(int status) { this.status = status; }
    }
}
