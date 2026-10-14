package com.meetingautomation.api.audio;

import com.meetingautomation.api.processing.AudioAssemblyUseCase;
import com.meetingautomation.api.session.MeetingSession;
import com.meetingautomation.api.session.MeetingSessionStore;
import com.meetingautomation.api.session.ProcessingFailure;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Streams a verified assembled object only while its failed processing session still has access. */
@Service
public final class AudioDownloadService {
    private final MeetingSessionStore sessions;
    private final AudioAssemblyUseCase audio;
    private final Clock clock;

    @Autowired
    public AudioDownloadService(MeetingSessionStore sessions, AudioAssemblyUseCase audio) {
        this(sessions, audio, Clock.systemUTC());
    }

    AudioDownloadService(MeetingSessionStore sessions, AudioAssemblyUseCase audio, Clock clock) {
        this.sessions = sessions;
        this.audio = audio;
        this.clock = clock;
    }

    public Download download(String sessionId) {
        return sessions.withSessionLock(sessionId, () -> {
            MeetingSession session = sessions.find(sessionId).orElseThrow(AudioSessionNotFoundException::new);
            ProcessingFailure failure = session.processingFailure();
            if (!"PROCESSING_FAILED".equals(session.status()) || failure == null || !failure.audioAvailable()
                    || failure.audioExpiresAt() == null || !failure.audioExpiresAt().isAfter(clock.instant())) {
                throw new AudioNotAvailableException();
            }
            AudioAssemblyStorage.Assembly assembly = audio.findAssembly(sessionId)
                    .orElseThrow(AudioNotAvailableException::new);
            if (assembly.bytes() < 1 || assembly.mimeType() == null
                    || !allowedAudioMime(assembly.mimeType())) throw new AudioNotAvailableException();
            return new Download(assembly, mediaType(assembly.mimeType()), filename(assembly.mimeType()),
                    output -> streamAndMark(sessionId, assembly, output));
        });
    }

    private void streamAndMark(String sessionId, AudioAssemblyStorage.Assembly assembly,
            java.io.OutputStream output) throws IOException {
        try {
            sessions.withSessionLock(sessionId, () -> {
                MeetingSession session = sessions.find(sessionId).orElse(null);
                ProcessingFailure failure = session == null ? null : session.processingFailure();
                if (session == null || !"PROCESSING_FAILED".equals(session.status()) || failure == null
                        || !failure.audioAvailable() || failure.audioExpiresAt() == null
                        || !failure.audioExpiresAt().isAfter(clock.instant())) {
                    throw new UncheckedIOException(new IOException("Audio is no longer available."));
                }
                AudioAssemblyStorage.Assembly current = audio.findAssembly(sessionId).orElse(null);
                if (current == null || !assembly.reference().equals(current.reference())
                        || assembly.bytes() != current.bytes()) {
                    throw new UncheckedIOException(new IOException("Audio is no longer available."));
                }
                try (InputStream input = audio.openAssembly(current)) {
                    input.transferTo(output);
                    output.flush();
                } catch (IOException | RuntimeException streamFailure) {
                    throw new UncheckedIOException(new IOException("Audio stream failed."));
                }
                sessions.markAudioDownloadCompleted(sessionId);
                return null;
            });
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static boolean allowedAudioMime(String mime) {
        try {
            MediaType parsed = MediaType.parseMediaType(mime);
            return "audio".equalsIgnoreCase(parsed.getType()) && extension(parsed) != null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static MediaType mediaType(String mime) {
        return MediaType.parseMediaType(mime);
    }

    private static String filename(String mime) {
        return "meeting-audio." + extension(MediaType.parseMediaType(mime));
    }

    private static String extension(MediaType type) {
        return switch (type.getSubtype().toLowerCase(Locale.ROOT)) {
            case "webm" -> "webm";
            case "mp4" -> "mp4";
            case "mpeg", "mp3", "mpga" -> "mp3";
            case "wav", "x-wav" -> "wav";
            case "ogg" -> "ogg";
            case "flac" -> "flac";
            default -> null;
        };
    }

    public record Download(AudioAssemblyStorage.Assembly assembly, MediaType mimeType, String filename,
            StreamingResponseBody body) {
    }
}
