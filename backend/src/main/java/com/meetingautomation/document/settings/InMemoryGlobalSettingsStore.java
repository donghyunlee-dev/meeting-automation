package com.meetingautomation.document.settings;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Runtime-only settings for the browser-owned operational-test connection.
 * A new Backend process intentionally starts unconfigured.
 */
@Component
@Conditional(GlobalSettingsStoreConditions.RuntimeSettingsRequired.class)
public final class InMemoryGlobalSettingsStore implements GlobalSettingsStore {
    private final Object lock = new Object();
    private final byte[] fingerprintKey = randomKey();
    private GlobalDocumentSettings snapshot = GlobalDocumentSettings.empty();

    @Override public GlobalDocumentSettings read() {
        synchronized (lock) {
            return snapshot;
        }
    }

    @Override public <T> T transaction(Function<GlobalDocumentSettings, Change<T>> mutation) {
        synchronized (lock) {
            Change<T> change = mutation.apply(snapshot);
            snapshot = change.snapshot();
            return change.result();
        }
    }

    @Override public String fingerprint(String normalizedRequest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(
                    ("setup-request/runtime-1:" + normalizedRequest).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw SettingsException.storage();
        }
    }

    private static byte[] randomKey() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return value;
    }
}
