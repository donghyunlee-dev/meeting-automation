package com.meetingautomation.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class OtpAuthenticationService {
    private static final int CODE_SPACE = 1_000_000;

    public enum VerificationStatus { VERIFIED, INVALID_CODE, EXPIRED, LOCKED, NOT_ISSUED }
    public record VerificationResult(VerificationStatus status, int retryAfterSeconds) { }
    public static final class PendingOtp {
        private final UUID requestId;
        private final String email;
        private final String code;
        private final byte[] verifier;

        private PendingOtp(UUID requestId, String email, String code, byte[] verifier) {
            this.requestId = requestId;
            this.email = email;
            this.code = code;
            this.verifier = verifier.clone();
        }
        public UUID requestId() { return requestId; }
        public String email() { return email; }
        public String code() { return code; }
        byte[] verifier() { return verifier.clone(); }
        @Override public String toString() { return "PendingOtp[requestId=" + requestId + ", email=redacted, code=redacted]"; }
    }

    private final OtpChallengeStore store;
    private final OtpCodeHasher hasher;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public OtpAuthenticationService(OtpChallengeStore store, OtpCodeHasher hasher) {
        this(store, hasher, Clock.systemUTC(), new SecureRandom());
    }

    public OtpAuthenticationService(OtpChallengeStore store, OtpCodeHasher hasher, Clock clock) {
        this(store, hasher, clock, new SecureRandom());
    }

    OtpAuthenticationService(OtpChallengeStore store, OtpCodeHasher hasher, Clock clock, SecureRandom random) {
        this.store = store;
        this.hasher = hasher;
        this.clock = clock;
        this.random = random;
    }

    public PendingOtp prepareIssue(String email) {
        String normalizedEmail = normalizeEmail(email);
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(CODE_SPACE));
        if (!hasher.configured()) throw new IllegalStateException("OTP authentication is not configured");
        UUID requestId = UUID.randomUUID();
        byte[] verifier = hasher.hash(normalizedEmail, code);
        store.prepare(normalizedEmail, requestId, verifier, clock.instant());
        return new PendingOtp(requestId, normalizedEmail, code, verifier);
    }

    public Instant activate(PendingOtp pending) {
        return store.activate(pending.email(), pending.requestId(), clock.instant());
    }

    public void cancel(PendingOtp pending) {
        store.cancel(pending.email(), pending.requestId());
    }

    public VerificationResult verify(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        if (code == null || !code.matches("\\d{6}")) {
            return store.verify(normalizedEmail, hasher.hash(normalizedEmail, "invalid"), clock.instant());
        }
        return store.verify(normalizedEmail, hasher.hash(normalizedEmail, code), clock.instant());
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
