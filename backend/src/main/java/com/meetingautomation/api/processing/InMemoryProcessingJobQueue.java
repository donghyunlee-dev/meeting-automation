package com.meetingautomation.api.processing;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.stereotype.Component;

/** Process-local queue. A later processing task consumes accepted jobs. */
@Component
public final class InMemoryProcessingJobQueue implements ProcessingJobQueue {
    private final BlockingQueue<ProcessingJob> jobs = new LinkedBlockingQueue<>();

    @Override
    public void enqueue(ProcessingJob job) {
        jobs.add(job);
    }
}
