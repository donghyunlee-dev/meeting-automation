package com.meetingautomation.minutes;

public interface MinutesGenerationProvider {
    GroundedMinutesResult generateMinutes(MinutesCommand command);
}
