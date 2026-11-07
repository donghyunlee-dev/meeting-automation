package com.meetingautomation.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class InMemoryOtpChallengeStore implements OtpChallengeStore {
    private static final Duration CODE_TTL = Duration.ofSeconds(60);
    private static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    private static final int MAX_FAILURES = 5;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    @Override
    public void prepare(String email, UUID requestId, byte[] verifier, Instant now) {
        states.compute(email, (key, current) -> {
            State state = current == null ? State.empty() : current;
            State unlocked = state.unlockIfExpired(now);
            if (unlocked.lockedUntil != null) throw rejected(OtpAuthenticationException.Reason.LOCKED, unlocked.lockedUntil, now);
            if (unlocked.pendingId != null) throw new OtpAuthenticationException(OtpAuthenticationException.Reason.ISSUE_IN_PROGRESS, 1);
            if (unlocked.lastSentAt != null && now.isBefore(unlocked.lastSentAt.plus(SEND_COOLDOWN))) {
                throw rejected(OtpAuthenticationException.Reason.COOLDOWN, unlocked.lastSentAt.plus(SEND_COOLDOWN), now);
            }
            return unlocked.pending(requestId, verifier);
        });
    }

    @Override
    public Instant activate(String email, UUID requestId, Instant now) {
        Instant expiresAt = now.plus(CODE_TTL);
        states.compute(email, (key, state) -> {
            if (state == null || !requestId.equals(state.pendingId)) return state;
            return state.activate(expiresAt, now);
        });
        State state = states.get(email);
        return state != null && requestId.equals(state.challengeId) ? state.expiresAt : null;
    }

    @Override
    public void cancel(String email, UUID requestId) {
        states.computeIfPresent(email, (key, state) -> requestId.equals(state.pendingId) ? state.cancelPending() : state);
    }

    @Override
    public OtpAuthenticationService.VerificationResult verify(String email, byte[] candidateVerifier, Instant now) {
        State current = states.get(email);
        if (current == null) return result(OtpAuthenticationService.VerificationStatus.NOT_ISSUED, 0);
        final OtpAuthenticationService.VerificationResult[] outcome = new OtpAuthenticationService.VerificationResult[1];
        states.computeIfPresent(email, (key, state) -> {
            State unlocked = state.unlockIfExpired(now);
            if (unlocked.lockedUntil != null) {
                outcome[0] = result(OtpAuthenticationService.VerificationStatus.LOCKED,
                        secondsUntil(now, unlocked.lockedUntil));
                return unlocked;
            }
            if (unlocked.challengeId == null || unlocked.expiresAt == null) {
                outcome[0] = result(OtpAuthenticationService.VerificationStatus.NOT_ISSUED, 0);
                return unlocked;
            }
            if (!now.isBefore(unlocked.expiresAt)) {
                outcome[0] = result(OtpAuthenticationService.VerificationStatus.EXPIRED, 0);
                return unlocked.withoutChallenge();
            }
            if (OtpCodeHasherMatch.matches(unlocked.verifier, candidateVerifier)) {
                outcome[0] = result(OtpAuthenticationService.VerificationStatus.VERIFIED, 0);
                return unlocked.verified();
            }
            int failures = unlocked.failures + 1;
            if (failures >= MAX_FAILURES) {
                Instant lockedUntil = now.plus(LOCK_DURATION);
                outcome[0] = result(OtpAuthenticationService.VerificationStatus.LOCKED, (int) LOCK_DURATION.toSeconds());
                return unlocked.lock(failures, lockedUntil);
            }
            outcome[0] = result(OtpAuthenticationService.VerificationStatus.INVALID_CODE, MAX_FAILURES - failures);
            return unlocked.withFailures(failures);
        });
        return outcome[0] == null ? result(OtpAuthenticationService.VerificationStatus.NOT_ISSUED, 0) : outcome[0];
    }

    private static OtpAuthenticationException rejected(OtpAuthenticationException.Reason reason, Instant until, Instant now) {
        return new OtpAuthenticationException(reason, secondsUntil(now, until));
    }

    private static int secondsUntil(Instant now, Instant until) {
        long seconds = Duration.between(now, until).toSeconds();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds + (Duration.between(now, until).minusSeconds(seconds).isZero() ? 0 : 1)));
    }

    private static OtpAuthenticationService.VerificationResult result(
            OtpAuthenticationService.VerificationStatus status, int retryAfterSeconds) {
        return new OtpAuthenticationService.VerificationResult(status, retryAfterSeconds);
    }

    private static final class OtpCodeHasherMatch {
        private static boolean matches(byte[] left, byte[] right) {
            return left != null && right != null && java.security.MessageDigest.isEqual(left, right);
        }
    }

    private record State(UUID pendingId, UUID challengeId, byte[] verifier, Instant expiresAt,
            Instant lastSentAt, int failures, Instant lockedUntil) {
        private static State empty() { return new State(null, null, null, null, null, 0, null); }
        private State pending(UUID id, byte[] value) {
            return new State(id, id, Arrays.copyOf(value, value.length), null, lastSentAt, failures, lockedUntil);
        }
        private State activate(Instant expiry, Instant sentAt) {
            return new State(null, challengeId, verifier, expiry, sentAt, failures, lockedUntil);
        }
        private State cancelPending() {
            return new State(null, null, null, null, lastSentAt, failures, lockedUntil);
        }
        private State withoutChallenge() {
            return new State(null, null, null, null, lastSentAt, failures, lockedUntil);
        }
        private State verified() {
            return new State(null, null, null, null, lastSentAt, 0, null);
        }
        private State withFailures(int value) {
            return new State(pendingId, challengeId, verifier, expiresAt, lastSentAt, value, lockedUntil);
        }
        private State lock(int value, Instant until) {
            return new State(null, null, null, null, lastSentAt, value, until);
        }
        private State unlockIfExpired(Instant now) {
            return lockedUntil != null && !now.isBefore(lockedUntil)
                    ? new State(pendingId, challengeId, verifier, expiresAt, lastSentAt, 0, null) : this;
        }
    }
}
