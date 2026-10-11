package com.meetingautomation;

import java.nio.file.*;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.io.TempDirFactory;

public final class OutsideRepositoryTempDirectory implements TempDirFactory {
    @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext extension) throws Exception {
        return Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".document-settings-test-");
    }
}
