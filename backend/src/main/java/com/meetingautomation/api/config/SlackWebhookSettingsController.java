package com.meetingautomation.api.config;

import com.meetingautomation.notification.slack.SlackNotificationAdapter;
import com.meetingautomation.notification.slack.SlackWebhookSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/slack")
public final class SlackWebhookSettingsController {
    private final SlackWebhookSettingsService settings;
    private final SlackNotificationAdapter adapter;

    public SlackWebhookSettingsController(SlackWebhookSettingsService settings, SlackNotificationAdapter adapter) {
        this.settings = settings;
        this.adapter = adapter;
    }

    @GetMapping
    public SlackWebhookSettingsResponse getSettings() {
        return SlackWebhookSettingsResponse.from(settings.snapshot());
    }

    @PutMapping
    public SlackWebhookSettingsResponse save(@Valid @RequestBody SlackWebhookSettingsRequest request) {
        return SlackWebhookSettingsResponse.from(settings.save(request.webhookUrl()));
    }

    @DeleteMapping
    public SlackWebhookSettingsResponse delete() {
        return SlackWebhookSettingsResponse.from(settings.delete());
    }

    @PostMapping("/test")
    public ResponseEntity<SlackConnectionTestResponse> testConnection() {
        boolean sent = adapter.sendTestMessage();
        String message = sent ? "Slack 채널에 연결 확인 메시지를 보냈습니다." : "Slack 연결 확인 메시지를 보내지 못했습니다.";
        return ResponseEntity.status(sent ? 200 : 502)
                .body(new SlackConnectionTestResponse(new SlackConnectionTestResponse.Data(sent, message)));
    }
}
