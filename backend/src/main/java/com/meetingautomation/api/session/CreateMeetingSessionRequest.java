package com.meetingautomation.api.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;

public record CreateMeetingSessionRequest(
        @NotBlank String title,
        @NotBlank String templateId,
        @NotEmpty List<@NotBlank String> participantIds,
        @NotBlank String timezone,
        @Positive long documentConnectionVersion,
        @NotBlank String recoveryKey,
        LocalDate meetingDate) {
    public CreateMeetingSessionRequest {
        participantIds = participantIds == null ? null : new ArrayList<>(participantIds);
    }
}
