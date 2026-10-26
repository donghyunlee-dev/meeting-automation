package com.meetingautomation.transcription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Transcodes an assembled audio stream into independently playable, bounded MP3 files. */
final class FfmpegAudioSegmenter implements AudioSegmenter {
    private static final Logger LOGGER = LoggerFactory.getLogger(FfmpegAudioSegmenter.class);
    private static final int SEGMENT_SECONDS = 600;
    private final String executable;

    FfmpegAudioSegmenter(String executable) { this.executable = executable; }

    @Override
    public SplitAudio split(TranscriptionCommand command, long maxSegmentBytes) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("transcription-");
            Path list = directory.resolve("segments.csv");
            Path output = directory.resolve("segment-%04d.mp3");
            List<String> args = List.of(executable, "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-i", "pipe:0", "-vn", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame",
                    "-b:a", "64k", "-f", "segment", "-segment_time", String.valueOf(SEGMENT_SECONDS),
                    "-segment_list", list.toString(), "-segment_list_type", "csv", "-reset_timestamps", "1",
                    output.toString());
            Process process;
            try {
                process = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException unavailable) {
                LOGGER.warn("Audio splitting failed traceId={} failureType=CONVERTER_UNAVAILABLE", command.traceId());
                throw new TranscriptionException(TranscriptionException.Code.CONFIGURATION_ERROR, false);
            }
            try {
                try (var stdin = process.getOutputStream()) {
                    if (command.audioStream() != null) {
                        if (command.audioStream().transferTo(stdin) != command.audioBytes()) {
                            throw new IOException("Audio length mismatch.");
                        }
                    } else {
                        try (var input = Files.newInputStream(command.audioReference())) { input.transferTo(stdin); }
                    }
                }
                if (!process.waitFor(10, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    LOGGER.warn("Audio splitting failed traceId={} failureType=CONVERSION_TIMEOUT", command.traceId());
                    throw new TranscriptionException(TranscriptionException.Code.PROCESSING_FAILED, true);
                }
                if (process.exitValue() != 0) {
                    LOGGER.warn("Audio splitting failed traceId={} failureType=CONVERSION_EXIT exitCode={}",
                            command.traceId(), process.exitValue());
                    throw new TranscriptionException(TranscriptionException.Code.INVALID_AUDIO, false);
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
            List<Segment> segments = readSegments(list, directory, command.meetingDurationMs(), maxSegmentBytes);
            LOGGER.info("Audio splitting succeeded traceId={} segmentCount={}", command.traceId(), segments.size());
            Path cleanupDirectory = directory;
            return new SplitAudio(segments, () -> deleteDirectory(cleanupDirectory));
        } catch (TranscriptionException failure) {
            deleteDirectory(directory);
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            deleteDirectory(directory);
            throw new TranscriptionException(TranscriptionException.Code.PROCESSING_FAILED, true);
        } catch (IOException failure) {
            LOGGER.warn("Audio splitting I/O failed traceId={} failureType={}",
                    command.traceId(), failure.getClass().getSimpleName());
            deleteDirectory(directory);
            throw new TranscriptionException(TranscriptionException.Code.PROCESSING_FAILED, true);
        } catch (RuntimeException failure) {
            LOGGER.warn("Audio splitting output invalid traceId={} failureType={}",
                    command.traceId(), failure.getClass().getSimpleName());
            deleteDirectory(directory);
            throw new TranscriptionException(TranscriptionException.Code.PROCESSING_FAILED, false);
        }
    }

    private static List<Segment> readSegments(Path list, Path directory, long meetingDurationMs,
            long maxSegmentBytes) throws IOException {
        List<Segment> segments = new ArrayList<>();
        long firstStart = -1;
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            String[] fields = line.split(",", -1);
            int index = segments.size();
            if (fields.length != 3 || !Path.of(fields[0]).getFileName().toString()
                    .equals("segment-%04d.mp3".formatted(index))) {
                throw new TranscriptionException(TranscriptionException.Code.INVALID_AUDIO, false);
            }
            Path path = directory.resolve("segment-%04d.mp3".formatted(index));
            long start = Math.round(Double.parseDouble(fields[1]) * 1_000);
            long end = Math.round(Double.parseDouble(fields[2]) * 1_000);
            if (firstStart < 0) firstStart = start;
            start -= firstStart;
            end -= firstStart;
            if (start < 0 || start >= meetingDurationMs || end <= start || Files.size(path) < 1
                    || Files.size(path) > maxSegmentBytes) {
                throw new TranscriptionException(TranscriptionException.Code.INVALID_AUDIO, false);
            }
            segments.add(new Segment(path, start, Math.min(end, meetingDurationMs) - start));
        }
        if (segments.isEmpty()) throw new TranscriptionException(TranscriptionException.Code.INVALID_AUDIO, false);
        return List.copyOf(segments);
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { /* Temporary files expire with the host. */ }
            });
        } catch (IOException ignored) { /* Temporary files expire with the host. */ }
    }
}
