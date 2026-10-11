package com.meetingautomation.document.bootstrap;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public final class BootstrapTaskRunner implements java.util.concurrent.Executor {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("document-bootstrap-").factory());
    @Override public void execute(Runnable command) { executor.execute(command); }
    @PreDestroy public void stop() { executor.shutdownNow(); }
}
