package com.meetingautomation.api.session;

import com.meetingautomation.api.idempotency.IdempotencyKeyConflictException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Replays a terminal create outcome for matching keys during this process lifetime. */
@Component
public final class MeetingSessionCreationCoordinator {
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    public MeetingSession execute(String key, Command command, Supplier<MeetingSession> operation) {
        Entry candidate = new Entry(command);
        Entry previous = entries.putIfAbsent(key, candidate);
        if (previous != null) {
            if (!previous.command.equals(command)) throw new IdempotencyKeyConflictException();
            return previous.await();
        }
        try {
            MeetingSession result = operation.get();
            candidate.outcome.complete(new Outcome(result, null));
            return result;
        } catch (RuntimeException failure) {
            candidate.outcome.complete(new Outcome(null, failure));
            throw failure;
        }
    }

    public record Command(String title, String templateId, List<String> participantIds,
            String timezone, String recoveryKey) {
        public Command {
            participantIds = List.copyOf(participantIds);
        }
    }

    static MeetingSession newSession(Command command) {
        return new MeetingSession(
                "ms_" + UUID.randomUUID(),
                1,
                "CREATED",
                new MeetingSession.Meeting(command.title(), command.templateId(),
                        command.participantIds(), command.timezone()));
    }

    private record Outcome(MeetingSession session, RuntimeException failure) { }

    private static final class Entry {
        private final Command command;
        private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();

        private Entry(Command command) { this.command = command; }

        private MeetingSession await() {
            Outcome result = outcome.join();
            if (result.failure() != null) throw result.failure();
            return result.session();
        }
    }
}
