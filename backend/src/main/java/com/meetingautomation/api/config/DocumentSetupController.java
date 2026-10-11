package com.meetingautomation.api.config;

import com.meetingautomation.document.settings.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-setup")
public final class DocumentSetupController {
    private final GlobalSettingsUseCase settings;
    private final Set<String> origins;
    public DocumentSetupController(GlobalSettingsUseCase settings, @Value("${ALLOWED_ORIGINS:}") String origins) {
        this.settings = settings;
        this.origins = new HashSet<>(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
    @GetMapping public ResponseEntity<Map<String, Object>> status() {
        var status = settings.status();
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (status.version() != null) response.eTag('"' + status.version().toString() + '"');
        return response.body(Map.of("data", status));
    }
    @PostMapping(value = "/drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> save(HttpServletRequest request, @RequestBody DocumentDraftInput input) {
        long version = validate(request);
        return result(settings.saveDraft(version, request.getHeader("Idempotency-Key"), input));
    }
    @PostMapping(value = "/drafts/{draftId}/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> test(HttpServletRequest request, @PathVariable String draftId, @RequestBody TestRequest input) {
        long version = validate(request);
        if (input.draftRevision() == null || input.draftRevision() < 1) throw SettingsException.invalid();
        return result(settings.testDraft(version, request.getHeader("Idempotency-Key"), draftId, input.draftRevision()));
    }
    private long validate(HttpServletRequest request) {
        if (!origins.contains(request.getHeader("Origin"))) throw new SettingsException("DOCUMENT_SETUP_ORIGIN_REJECTED", 403);
        String version = request.getHeader("If-Match");
        String key = request.getHeader("Idempotency-Key");
        if (!"true".equals(request.getHeader("X-Document-Setup-Request")) || version == null
                || !version.matches("\"[0-9]{1,18}\"") || key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
            throw SettingsException.invalid();
        try { return Long.parseLong(version.substring(1, version.length() - 1)); }
        catch (NumberFormatException ignored) { throw SettingsException.invalid(); }
    }
    private static ResponseEntity<String> result(GlobalSettingsUseCase.MutationResult result) {
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).eTag('"' + Long.toString(result.version()) + '"').body(result.responseJson());
    }
    public record TestRequest(Long draftRevision) { }
}
