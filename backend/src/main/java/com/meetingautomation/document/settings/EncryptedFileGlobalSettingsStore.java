package com.meetingautomation.document.settings;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class EncryptedFileGlobalSettingsStore implements GlobalSettingsStore {
    private static final Object PROCESS_LOCK = new Object();
    private static final byte[] AAD = "meeting-automation-settings/schema-1".getBytes(StandardCharsets.UTF_8);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;
    private final byte[] key;
    private final boolean render;
    private final SnapshotWriter writer;

    @Autowired
    public EncryptedFileGlobalSettingsStore(@Value("${DOCUMENT_SETTINGS_DIR:}") String directory,
            @Value("${DOCUMENT_SETTINGS_ENCRYPTION_KEY:}") String encodedKey,
            @Value("${RENDER:false}") boolean render) {
        this(directory, encodedKey, render, new AtomicSnapshotWriter());
    }
    public EncryptedFileGlobalSettingsStore(String directory, String encodedKey, boolean render, SnapshotWriter writer) {
        Path candidate = null;
        byte[] decoded = null;
        try {
            candidate = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
            decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32 || !Path.of(directory).isAbsolute()) decoded = null;
        } catch (RuntimeException ignored) { decoded = null; }
        this.directory = candidate;
        this.key = decoded;
        this.render = render;
        this.writer = writer;
    }

    @Override public GlobalDocumentSettings read() {
        synchronized (PROCESS_LOCK) { return load(); }
    }
    @Override public <T> T transaction(Function<GlobalDocumentSettings, Change<T>> mutation) {
        synchronized (PROCESS_LOCK) {
            GlobalDocumentSettings current = load();
            Change<T> change = mutation.apply(current);
            if (change.snapshot() != current) save(change.snapshot());
            return change.result();
        }
    }
    @Override public String fingerprint(String normalizedRequest) {
        if (key == null) throw SettingsException.storage();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(
                    ("setup-request/schema-1:" + normalizedRequest).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) { throw SettingsException.storage(); }
    }
    private void validateDirectory() throws Exception {
        if (directory == null || key == null || !Files.isDirectory(directory)
                || !Files.isWritable(directory) || Files.isSymbolicLink(directory)) throw SettingsException.storage();
        Path real = directory.toRealPath();
        for (Path ancestor = real; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"))) throw SettingsException.storage();
        }
        // Render's root filesystem is ephemeral. A distinct mounted disk must be present.
        if (render && Files.getFileStore(real).equals(Files.getFileStore(real.getRoot()))) throw SettingsException.storage();
        Path probe = Files.createTempFile(directory, ".writable-", ".tmp");
        Files.delete(probe);
    }
    private GlobalDocumentSettings load() {
        try {
            validateDirectory();
            Path file = directory.resolve("settings.enc");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return GlobalDocumentSettings.empty();
            if (Files.isSymbolicLink(file) || Files.size(file) > 16 * 1024 * 1024) throw SettingsException.storage();
            Envelope envelope = JSON.readValue(Files.readAllBytes(file), Envelope.class);
            if (envelope.schemaVersion() != 1) throw SettingsException.storage();
            byte[] nonce = Base64.getDecoder().decode(envelope.nonce());
            if (nonce.length != 12) throw SettingsException.storage();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            GlobalDocumentSettings settings = JSON.readValue(cipher.doFinal(
                Base64.getDecoder().decode(envelope.ciphertext())), GlobalDocumentSettings.class);
            if (settings.schemaVersion() != 1 || settings.version() < 0 || settings.idempotencyRecords() == null)
                throw SettingsException.storage();
            return settings;
        } catch (Exception ignored) { throw SettingsException.storage(); }
    }
    private void save(GlobalDocumentSettings snapshot) {
        try {
            validateDirectory();
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(JSON.writeValueAsBytes(snapshot));
            byte[] bytes = JSON.writeValueAsBytes(new Envelope(1, Base64.getEncoder().encodeToString(nonce),
                Base64.getEncoder().encodeToString(ciphertext)));
            if (bytes.length > 16 * 1024 * 1024) throw SettingsException.storage();
            writer.write(directory, bytes);
        } catch (Exception ignored) { throw SettingsException.storage(); }
    }
    private record Envelope(int schemaVersion, String nonce, String ciphertext) { }
}
