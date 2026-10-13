package com.meetingautomation.api.processing;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Process-local asynchronous handoff to the processing job runner. */
@Component
public final class InMemoryProcessingJobQueue implements ProcessingJobQueue {
    private final ProcessingJobRunner runner;
    private final Executor executor;

    public InMemoryProcessingJobQueue(
            ProcessingJobRunner runner, @Qualifier("processingJobExecutor") Executor executor) {
        this.runner = runner;
        this.executor = executor;
    }

    @Override
    public void enqueue(ProcessingJob job) {
        if (job == null) throw new IllegalArgumentException("Processing job is invalid.");
        try {
            executor.execute(() -> runner.run(job));
        } catch (RuntimeException rejected) {
            throw new IllegalStateException("Processing job could not be queued.");
        }
    }
}
