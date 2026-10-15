package com.meetingautomation.api.audio;

/** Storage provider contract for validating and accepting a browser-uploaded private Blob object. */
public interface ClientUploadedAudioStoragePort {
    void confirmClientUpload(String sessionId, int sequence, String mimeType, long bytes, String sha256);
}
