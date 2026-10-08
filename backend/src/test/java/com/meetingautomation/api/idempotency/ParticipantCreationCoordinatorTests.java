package com.meetingautomation.api.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.meetingautomation.document.CreateParticipantCommand;
import com.meetingautomation.document.DocumentProviderException;
import com.meetingautomation.document.Participant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParticipantCreationCoordinatorTests {
    private static final CreateParticipantCommand COMMAND =
            new CreateParticipantCommand("Ada", "ada@example.test");

    @Test
    void concurrentSameKeyRequestRunsProviderCreateOnceAndSharesResult() throws Exception {
        ParticipantCreationCoordinator coordinator = new ParticipantCreationCoordinator();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch secondCallerReady = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            Future<Participant> first = executor.submit(() -> coordinator.execute("key", COMMAND, () -> {
                calls.incrementAndGet();
                operationStarted.countDown();
                await(releaseOperation);
                return new Participant("pt-1", COMMAND.name(), COMMAND.email());
            }));
            operationStarted.await(5, TimeUnit.SECONDS);
            Future<Participant> second = executor.submit(() -> {
                secondCallerReady.countDown();
                return coordinator.execute("key", COMMAND, () -> {
                    calls.incrementAndGet();
                    return new Participant("pt-2", COMMAND.name(), COMMAND.email());
                });
            });
            secondCallerReady.await(5, TimeUnit.SECONDS);
            releaseOperation.countDown();

            Participant firstResult = first.get(5, TimeUnit.SECONDS);
            Participant secondResult = second.get(5, TimeUnit.SECONDS);
            assertEquals(new Participant("pt-1", "Ada", "ada@example.test"), firstResult);
            assertSame(firstResult, secondResult);
            assertEquals(1, calls.get());
        } finally {
            releaseOperation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void differentKeysAreIndependentCreateOperations() {
        ParticipantCreationCoordinator coordinator = new ParticipantCreationCoordinator();
        AtomicInteger calls = new AtomicInteger();

        coordinator.execute("key-a", COMMAND, () -> {
            calls.incrementAndGet();
            return new Participant("pt-a", COMMAND.name(), COMMAND.email());
        });
        coordinator.execute("key-b", COMMAND, () -> {
            calls.incrementAndGet();
            return new Participant("pt-b", COMMAND.name(), COMMAND.email());
        });

        assertEquals(2, calls.get());
    }

    @Test
    void failedUncertainOperationIsReplayedWithoutAnotherAttempt() {
        ParticipantCreationCoordinator coordinator = new ParticipantCreationCoordinator();
        AtomicInteger calls = new AtomicInteger();
        DocumentProviderException first = assertThrows(DocumentProviderException.class,
                () -> coordinator.execute("key", COMMAND, () -> {
                    calls.incrementAndGet();
                    throw DocumentProviderException.documentFailed(false);
                }));
        DocumentProviderException replay = assertThrows(DocumentProviderException.class,
                () -> coordinator.execute("key", COMMAND, () -> {
                    calls.incrementAndGet();
                    return new Participant("unexpected", COMMAND.name(), COMMAND.email());
                }));

        assertEquals("DOCUMENT_FAILED", first.code());
        assertSame(first, replay);
        assertEquals(1, calls.get());
    }

    @Test
    void sameKeyWithDifferentPayloadConflicts() {
        ParticipantCreationCoordinator coordinator = new ParticipantCreationCoordinator();
        coordinator.execute("key", COMMAND,
                () -> new Participant("pt-1", COMMAND.name(), COMMAND.email()));

        assertThrows(IdempotencyKeyConflictException.class,
                () -> coordinator.execute("key", new CreateParticipantCommand("Grace", "grace@example.test"),
                        () -> new Participant("pt-2", "Grace", "grace@example.test")));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", interrupted);
        }
    }
}
