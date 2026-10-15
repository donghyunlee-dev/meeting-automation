package com.meetingautomation.api.audio;

import java.time.Instant;

/** Supports atomic expiry checks when a storage provider owns durable lease metadata. */
public interface ExpiredAudioStoragePort {
    boolean deleteIfExpired(String objectId, Instant now);
}
