package com.meetingautomation.api.participant;

import java.util.regex.Pattern;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import tools.jackson.databind.JsonNode;

/** Parses PATCH field presence separately from its value so null is not mistaken for omission. */
public record ParticipantUpdateRequest(String name, String email) {
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public static ParticipantUpdateRequest parse(JsonNode body) throws BindException {
        BindException errors = new BindException(body, "request");
        if (body == null || !body.isObject()) {
            errors.addError(new FieldError("request", "request", "Invalid"));
            throw errors;
        }
        boolean hasName = body.has("name");
        boolean hasEmail = body.has("email");
        if (!hasName && !hasEmail) {
            errors.addError(new FieldError("request", "request", "NotEmpty"));
        }
        String name = hasName ? trimmedText(body.get("name"), "name", errors) : null;
        String email = hasEmail ? trimmedText(body.get("email"), "email", errors) : null;
        if (hasName && (name == null || name.isBlank())) {
            errors.addError(new FieldError("request", "name", "NotBlank"));
        }
        if (hasEmail && (email == null || !EMAIL_ADDRESS.matcher(email).matches())) {
            errors.addError(new FieldError("request", "email", "Email"));
        }
        if (errors.hasErrors()) {
            throw errors;
        }
        return new ParticipantUpdateRequest(name, email);
    }

    private static String trimmedText(JsonNode value, String field, BindException errors) {
        if (value == null || !value.isTextual()) {
            errors.addError(new FieldError("request", field, "TypeMismatch"));
            return null;
        }
        return value.textValue().trim();
    }
}
