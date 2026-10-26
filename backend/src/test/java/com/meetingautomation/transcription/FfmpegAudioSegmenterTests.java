package com.meetingautomation.transcription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegAudioSegmenterTests {
    @TempDir Path directory;

    @Test
    void readsSegmentTimingAndDeletesTemporaryAudioAfterUse() throws Exception {
        Path executable = fakeFfmpeg("""
                cat >/dev/null
                printf 'one' > "$outdir/segment-0000.mp3"
                printf 'two' > "$outdir/segment-0001.mp3"
                printf 'segment-0000.mp3,0.000,600.000\nsegment-0001.mp3,600.000,900.000\n' > "$outdir/segments.csv"
                """);
        var segmenter = new FfmpegAudioSegmenter(executable.toString());
        var command = new TranscriptionCommand(new ByteArrayInputStream(new byte[5]), 5,
                "audio/webm", "ko", 900_000);
        Path output;
        try (var split = segmenter.split(command, 4)) {
            assertEquals(2, split.segments().size());
            assertEquals(0, split.segments().get(0).startMs());
            assertEquals(600_000, split.segments().get(1).startMs());
            assertEquals(300_000, split.segments().get(1).durationMs());
            output = split.segments().get(0).path();
            assertTrue(Files.exists(output));
        }
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsSegmentOverProviderLimitAndCleansTemporaryAudio() throws Exception {
        Path executable = fakeFfmpeg("""
                cat >/dev/null
                printf 'oversize' > "$outdir/segment-0000.mp3"
                printf 'segment-0000.mp3,0.000,1.000\n' > "$outdir/segments.csv"
                """);
        var segmenter = new FfmpegAudioSegmenter(executable.toString());
        var failure = assertThrows(TranscriptionException.class, () -> segmenter.split(
                new TranscriptionCommand(new ByteArrayInputStream(new byte[5]), 5,
                        "audio/webm", "ko", 1_000), 4));
        assertEquals(TranscriptionException.Code.INVALID_AUDIO, failure.code());
    }

    @Test
    void reportsMissingConverterAsConfigurationFailure() {
        var segmenter = new FfmpegAudioSegmenter(directory.resolve("missing-ffmpeg").toString());
        var failure = assertThrows(TranscriptionException.class, () -> segmenter.split(
                new TranscriptionCommand(new ByteArrayInputStream(new byte[5]), 5,
                        "audio/webm", "ko", 1_000), 24_000_000));
        assertEquals(TranscriptionException.Code.CONFIGURATION_ERROR, failure.code());
    }

    @Test
    void splitsPlayableWebmAcrossTenMinuteBoundaryWhenFfmpegIsAvailable() throws Exception {
        String executable = System.getenv("FFMPEG_TEST_BINARY");
        Assumptions.assumeTrue(executable != null && Files.isExecutable(Path.of(executable)));
        Path source = directory.resolve("source.webm");
        int generated = new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                "-i", "sine=frequency=500:duration=620", "-c:a", "libopus", "-b:a", "32k",
                "-y", source.toString()).inheritIO().start().waitFor();
        assertEquals(0, generated);

        var segmenter = new FfmpegAudioSegmenter(executable);
        try (var split = segmenter.split(new TranscriptionCommand(source, "audio/webm", "ko", 620_000),
                24_000_000)) {
            assertEquals(2, split.segments().size());
            assertEquals(0, split.segments().get(0).startMs());
            assertTrue(split.segments().get(1).startMs() >= 599_000);
            assertTrue(split.segments().get(1).startMs() <= 601_000);
            assertTrue(split.segments().stream().allMatch(part -> Files.isRegularFile(part.path())));
        }
    }

    private Path fakeFfmpeg(String body) throws Exception {
        Path executable = directory.resolve("ffmpeg-test-" + System.nanoTime());
        Files.writeString(executable, "#!/bin/sh\noutdir=''\nfor arg in \"$@\"; do\n"
                + " case \"$arg\" in *segment-%04d.mp3) outdir=${arg%/*};; esac\ndone\n" + body);
        executable.toFile().setExecutable(true);
        return executable;
    }
}
