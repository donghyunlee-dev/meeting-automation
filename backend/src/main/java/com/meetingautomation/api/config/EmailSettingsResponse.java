package com.meetingautomation.api.config;

import com.meetingautomation.email.EmailSettingsService;

public record EmailSettingsResponse(Data data) {
    public static EmailSettingsResponse from(EmailSettingsService.Snapshot snapshot) {
        return new EmailSettingsResponse(new Data(snapshot.configured(), snapshot.connectionVerified(),
                snapshot.maskedClientId(), snapshot.maskedClientSecret(), snapshot.maskedRefreshToken(),
                snapshot.senderAddress(), snapshot.verifiedAt()));
    }

    public record Data(boolean configured, boolean connectionVerified, String maskedClientId,
            String maskedClientSecret, String maskedRefreshToken, String senderAddress, String verifiedAt) { }
}
