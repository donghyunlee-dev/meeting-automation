package com.meetingautomation.api.session;

interface MinutesRegenerationQueue {
    void enqueue(MinutesRegenerationJob job);
}

