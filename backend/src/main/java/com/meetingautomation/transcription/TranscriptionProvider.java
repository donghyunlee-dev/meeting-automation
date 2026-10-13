package com.meetingautomation.transcription;

public interface TranscriptionProvider {
    ProviderTranscriptionResponse transcribeWithDiarization(TranscriptionCommand command);
}
