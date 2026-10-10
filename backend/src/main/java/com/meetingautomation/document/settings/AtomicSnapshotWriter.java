package com.meetingautomation.document.settings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

public final class AtomicSnapshotWriter implements SnapshotWriter {
    @Override public void write(Path directory, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, ".settings-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, directory.resolve("settings.enc"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            // Windows denies directory channels; file fsync and same-volume atomic replacement remain mandatory.
            if (!System.getProperty("os.name").startsWith("Windows")) {
                try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
