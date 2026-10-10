package com.meetingautomation.document.settings;

import java.io.IOException;
import java.nio.file.Path;

/** Commits a complete ciphertext snapshot, leaving the previous file intact before commit. */
@FunctionalInterface
public interface SnapshotWriter {
    void write(Path directory, byte[] encryptedSnapshot) throws IOException;
}
