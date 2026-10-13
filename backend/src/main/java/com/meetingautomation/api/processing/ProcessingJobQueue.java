package com.meetingautomation.api.processing;

public interface ProcessingJobQueue {
    void enqueue(ProcessingJob job);
}
