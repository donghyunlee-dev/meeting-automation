package com.meetingautomation.api.idempotency;

import com.meetingautomation.document.CreateParticipantCommand;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.Participant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Coordinates participant create calls and replays their terminal outcome within this process lifetime. */
@Component
public final class ParticipantCreationCoordinator {
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    public Participant execute(
            String key,
            CreateParticipantCommand command,
            Supplier<Participant> createOperation) {
        Entry candidate = new Entry(command);
        Entry existing = entries.putIfAbsent(key, candidate);
        if (existing != null) {
            if (!existing.command().equals(command)) {
                throw new IdempotencyKeyConflictException();
            }
            return existing.await();
        }

        try {
            Participant participant = createOperation.get();
            candidate.complete(new Outcome(participant, null));
            return participant;
        } catch (RuntimeException failure) {
            DocumentProviderException safeFailure = failure instanceof DocumentProviderException providerFailure
                    ? providerFailure
                    : DocumentProviderException.documentFailed(false, failure);
            candidate.complete(new Outcome(null, safeFailure));
            throw safeFailure;
        }
    }

    private record Outcome(Participant participant, DocumentProviderException failure) { }

    private static final class Entry {
        private final CreateParticipantCommand command;
        private final CompletableFuture<Outcome> outcome = new CompletableFuture<>();

        private Entry(CreateParticipantCommand command) {
            this.command = command;
        }

        private CreateParticipantCommand command() {
            return command;
        }

        private void complete(Outcome result) {
            outcome.complete(result);
        }

        private Participant await() {
            Outcome result = outcome.join();
            if (result.failure() != null) throw result.failure();
            return result.participant();
        }
    }
}
