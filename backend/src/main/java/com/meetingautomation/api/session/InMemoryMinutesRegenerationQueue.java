package com.meetingautomation.api.session;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Process-local asynchronous handoff for accepted Minutes regeneration operations. */
@Component
final class InMemoryMinutesRegenerationQueue implements MinutesRegenerationQueue {
    private final MinutesRegenerationJobRunner runner;
    private final Executor executor;

    InMemoryMinutesRegenerationQueue(MinutesRegenerationJobRunner runner,
            @Qualifier("processingJobExecutor") Executor executor) {
        this.runner = runner;
        this.executor = executor;
    }

    @Override
    public void enqueue(MinutesRegenerationJob job) {
        if (job == null) throw new IllegalArgumentException("Minutes regeneration job is invalid.");
        try {
            executor.execute(() -> runner.run(job));
        } catch (RuntimeException rejected) {
            throw new IllegalStateException("Minutes regeneration job could not be queued.");
        }
    }
}

