package com.meetingautomation.api.audio;

import java.util.List;

public record AudioUploadStatusResponse(Data data) {
    public record Data(List<Integer> receivedSequences, int receivedChunks, long totalBytes) {
        public Data {
            receivedSequences = List.copyOf(receivedSequences);
        }
    }
}
