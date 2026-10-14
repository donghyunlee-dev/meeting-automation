package com.meetingautomation.api.session;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
final class InMemoryPublishQueue implements PublishQueue {
    private final PublishJobRunner runner;
    private final Executor executor;
    InMemoryPublishQueue(PublishJobRunner runner, @Qualifier("processingJobExecutor") Executor executor) {
        this.runner = runner; this.executor = executor;
    }
    @Override public void enqueue(PublishJob job) {
        try { executor.execute(() -> runner.run(job)); }
        catch (RuntimeException rejected) { throw new IllegalStateException("Document publish job could not be queued."); }
    }
}
