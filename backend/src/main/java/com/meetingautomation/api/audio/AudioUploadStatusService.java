package com.meetingautomation.api.audio;

import com.meetingautomation.api.session.MeetingSessionStore;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class AudioUploadStatusService {
    private final MeetingSessionStore sessionStore;
    private final PrivateAudioStoragePort storage;

    public AudioUploadStatusService(MeetingSessionStore sessionStore, PrivateAudioStoragePort storage) {
        this.sessionStore = sessionStore;
        this.storage = storage;
    }

    public AudioUploadStatusResponse getStatus(String sessionId) {
        sessionStore.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
        List<PrivateAudioStoragePort.StoredObject> chunks = storage.listForSession(sessionId).stream()
                .filter(object -> object.type() == PrivateAudioStoragePort.ObjectType.CHUNK)
                .sorted(java.util.Comparator.comparingInt(PrivateAudioStoragePort.StoredObject::sequence))
                .toList();
        List<Integer> sequences = chunks.stream().map(PrivateAudioStoragePort.StoredObject::sequence).toList();
        long totalBytes = 0;
        try {
            for (PrivateAudioStoragePort.StoredObject chunk : chunks) {
                totalBytes = Math.addExact(totalBytes, chunk.bytes());
            }
        } catch (ArithmeticException overflow) {
            throw new AudioStorageException();
        }
        return new AudioUploadStatusResponse(
                new AudioUploadStatusResponse.Data(sequences, sequences.size(), totalBytes));
    }
}
