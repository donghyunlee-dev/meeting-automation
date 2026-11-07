package com.meetingautomation.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class OtpCodeHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    private final boolean configured;

    public OtpCodeHasher(@Value("${OTP_HMAC_SECRET:}") String secret) {
        byte[] supplied = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        configured = supplied.length >= 32;
        this.secret = configured ? supplied : new SecureRandom().generateSeed(32);
    }

    boolean configured() { return configured; }

    byte[] hash(String normalizedEmail, String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal((normalizedEmail + "\n" + code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("OTP verifier is unavailable", unavailable);
        }
    }

}
