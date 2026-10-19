package com.meetingautomation.api.audio;

import com.meetingautomation.api.session.MeetingSessionStore;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class AudioUploadStatusService {
    private final MeetingSessionStore sessionStore;
    private final AudioChunkFileStore fileStore;

    public AudioUploadStatusService(MeetingSessionStore sessionStore, AudioChunkFileStore fileStore) {
        this.sessionStore = sessionStore;
        this.fileStore = fileStore;
    }

    public AudioUploadStatusResponse getStatus(String sessionId) {
        sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
        List<AudioChunkFileStore.CompletedChunk> chunks = fileStore.completedChunks(sessionId);
        List<Integer> sequences = chunks.stream().map(AudioChunkFileStore.CompletedChunk::sequence).toList();
        long totalBytes = 0;
        try {
            for (AudioChunkFileStore.CompletedChunk chunk : chunks) {
                totalBytes = Math.addExact(totalBytes, chunk.bytes());
            }
        } catch (ArithmeticException overflow) {
            throw new AudioStorageException();
        }
        return new AudioUploadStatusResponse(
                new AudioUploadStatusResponse.Data(sequences, sequences.size(), totalBytes));
    }
}
