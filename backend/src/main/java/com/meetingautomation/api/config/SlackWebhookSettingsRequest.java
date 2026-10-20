package com.meetingautomation.api.config;

import jakarta.validation.constraints.NotBlank;

public record SlackWebhookSettingsRequest(@NotBlank String webhookUrl) { }
