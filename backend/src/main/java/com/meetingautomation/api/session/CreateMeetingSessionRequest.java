package com.meetingautomation.api.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;

public record CreateMeetingSessionRequest(
        @NotBlank String title,
        @NotBlank String templateId,
        @NotEmpty List<@NotBlank String> participantIds,
        @NotBlank String timezone,
        @NotBlank String recoveryKey) {
    public CreateMeetingSessionRequest {
        participantIds = participantIds == null ? null : new ArrayList<>(participantIds);
    }
}
