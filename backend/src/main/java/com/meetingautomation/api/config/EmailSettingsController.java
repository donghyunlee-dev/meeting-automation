package com.meetingautomation.api.config;

import com.meetingautomation.email.EmailProvider;
import com.meetingautomation.email.EmailSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/email")
public final class EmailSettingsController {
    private final EmailSettingsService settings;
    private final EmailProvider provider;

    public EmailSettingsController(EmailSettingsService settings, EmailProvider provider) {
        this.settings = settings;
        this.provider = provider;
    }

    @GetMapping
    public EmailSettingsResponse getSettings() { return EmailSettingsResponse.from(settings.snapshot()); }

    @PutMapping
    public EmailSettingsResponse save(@Valid @RequestBody EmailSettingsRequest request) {
        return EmailSettingsResponse.from(settings.save(request.clientId(), request.clientSecret(),
                request.refreshToken(), request.senderAddress()));
    }

    @DeleteMapping
    public EmailSettingsResponse delete() { return EmailSettingsResponse.from(settings.delete()); }

    @PostMapping("/test")
    public ResponseEntity<EmailConnectionTestResponse> testConnection() {
        boolean connected = provider.validateConnection();
        settings.markConnectionVerified(connected);
        String message = connected ? "Gmail OAuth2 연결이 확인되었습니다." : "Gmail OAuth2 연결을 확인하지 못했습니다.";
        return ResponseEntity.status(connected ? 200 : 502)
                .body(new EmailConnectionTestResponse(new EmailConnectionTestResponse.Data(connected, message)));
    }

    @PostMapping("/test-message")
    public ResponseEntity<EmailTestMessageResponse> sendTestMessage() {
        boolean sent = provider.sendTestMessage();
        settings.markConnectionVerified(sent);
        String message = sent ? "발신 이메일 주소로 테스트 메일을 보냈습니다." : "테스트 메일을 보내지 못했습니다.";
        return ResponseEntity.status(sent ? 200 : 502)
                .body(new EmailTestMessageResponse(new EmailTestMessageResponse.Data(sent, message)));
    }
}
