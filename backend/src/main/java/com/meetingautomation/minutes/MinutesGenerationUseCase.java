package com.meetingautomation.minutes;

import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.template.TemplateCatalog;
import com.meetingautomation.api.template.TemplateDefinition;
import com.meetingautomation.document.StructuredMinutes;
import org.springframework.stereotype.Service;

/** Builds the minimum provider command and validates its transcript-grounded response. */
@Service
public final class MinutesGenerationUseCase {
    private final TemplateCatalog templateCatalog;
    private final MinutesGenerationProvider provider;
    private final GroundedMinutesResultNormalizer normalizer;

    public MinutesGenerationUseCase(TemplateCatalog templateCatalog,
            MinutesGenerationProvider provider, GroundedMinutesResultNormalizer normalizer) {
        if (templateCatalog == null || provider == null || normalizer == null) {
            throw new IllegalArgumentException("Minutes generation dependencies are invalid.");
        }
        this.templateCatalog = templateCatalog;
        this.provider = provider;
        this.normalizer = normalizer;
    }

    public StructuredMinutes generate(MeetingSession session) {
        if (session == null || session.meeting() == null) {
            throw failure(MinutesGenerationException.Code.PROCESSING_FAILED, false);
        }
        TemplateDefinition template;
        try {
            template = templateCatalog.loadTemplate(session.meeting().templateId());
        } catch (RuntimeException unavailableTemplate) {
            throw failure(MinutesGenerationException.Code.CONFIGURATION_ERROR, false);
        }
        if (session.meeting().templateVersion() == null
                || session.meeting().templateVersion().isBlank()
                || !session.meeting().templateVersion().equals(template.version())) {
            throw failure(MinutesGenerationException.Code.CONFIGURATION_ERROR, false);
        }
        MinutesCommand command;
        try {
            command = new MinutesCommand(template.id(), session.meeting().templateVersion(), template.content(),
                    session.meeting().participantReferences(), java.util.Map.of(), session.transcript());
        } catch (RuntimeException invalidInput) {
            throw failure(MinutesGenerationException.Code.PROCESSING_FAILED, false);
        }
        if (command.transcript().isEmpty()) {
            return new StructuredMinutes(template.id(), template.version(), "",
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        GroundedMinutesResult result;
        try {
            result = provider.generateMinutes(command);
        } catch (MinutesGenerationException providerFailure) {
            throw providerFailure;
        } catch (RuntimeException providerFailure) {
            throw failure(MinutesGenerationException.Code.PROCESSING_FAILED, false);
        }
        try {
            return normalizer.normalize(command, result);
        } catch (RuntimeException invalidResult) {
            throw failure(MinutesGenerationException.Code.PROCESSING_FAILED, false);
        }
    }

    private static MinutesGenerationException failure(MinutesGenerationException.Code code, boolean retryable) {
        return new MinutesGenerationException(code, retryable);
    }
}
