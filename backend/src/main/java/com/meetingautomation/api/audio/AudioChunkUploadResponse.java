package com.meetingautomation.api.audio;

public record AudioChunkUploadResponse(Data data) {
    public record Data(int sequence, boolean received) {
    }
}
