package com.meetingautomation.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TranscriptionCommandTests {
    @Test
    void retainsAudioMetadataWithoutExposingTheProtectedFilePath() {
        Path protectedAudio = Path.of("private", "assembled-audio.webm");

        TranscriptionCommand command = new TranscriptionCommand(protectedAudio, " audio/webm ", "ko", 1250);

        assertEquals(protectedAudio, command.audioReference());
        assertEquals("audio/webm", command.mimeType());
        assertEquals("ko", command.languageHint());
        assertEquals(1250, command.meetingDurationMs());
        assertFalse(command.toString().contains(protectedAudio.toString()));
    }

    @Test
    void rejectsInvalidDurationWithSafeMessage() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new TranscriptionCommand(Path.of("private", "audio.webm"), "audio/webm", null, 0));

        assertEquals("Transcription command is invalid.", failure.getMessage());
        assertFalse(failure.getMessage().contains("audio.webm"));
    }
}
