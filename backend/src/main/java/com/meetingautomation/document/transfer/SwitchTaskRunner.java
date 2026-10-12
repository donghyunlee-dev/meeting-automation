package com.meetingautomation.document.transfer;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

@Component
public final class SwitchTaskRunner implements java.util.concurrent.Executor {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("document-switch-").factory());
    @Override public void execute(Runnable command) { executor.execute(command); }
    @PreDestroy public void stop() { executor.shutdownNow(); }
}
