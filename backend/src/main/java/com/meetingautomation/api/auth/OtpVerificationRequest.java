package com.meetingautomation.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OtpVerificationRequest(@NotBlank @Email @Size(max = 254) String email,
        @Size(max = 32) String code) {
    public OtpVerificationRequest {
        email = email == null ? null : email.trim();
    }
}
