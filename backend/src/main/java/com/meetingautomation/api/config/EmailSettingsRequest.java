package com.meetingautomation.api.config;

import jakarta.validation.constraints.NotBlank;

public record EmailSettingsRequest(@NotBlank String clientId, @NotBlank String clientSecret,
        @NotBlank String refreshToken, @NotBlank String senderAddress) { }
