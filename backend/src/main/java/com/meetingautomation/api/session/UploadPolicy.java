package com.meetingautomation.api.session;

import java.util.List;

public record UploadPolicy(int chunkDurationSeconds, int maxChunkBytes, List<String> acceptedMimeTypes) {
    public UploadPolicy {
        acceptedMimeTypes = List.copyOf(acceptedMimeTypes);
    }
}
