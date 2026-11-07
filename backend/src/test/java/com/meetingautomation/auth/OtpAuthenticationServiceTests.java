package com.meetingautomation.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OtpAuthenticationServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static final String EMAIL = "leedh84@gmail.com";

    @Test
    void codeIsNotUsableUntilMailWasSentAndExpiresSixtySecondsAfterActivation() {
        MutableClock clock = new MutableClock(NOW);
        OtpAuthenticationService service = service(clock);
        var pending = service.prepareIssue("  Leedh84@Gmail.com ");

        assertEquals("leedh84@gmail.com", pending.email());
        assertEquals(OtpAuthenticationService.VerificationStatus.NOT_ISSUED,
                service.verify(EMAIL, pending.code()).status());

        clock.advanceSeconds(18);
        Instant expiresAt = service.activate(pending);
        assertEquals(clock.instant().plusSeconds(60), expiresAt);
        assertEquals(OtpAuthenticationService.VerificationStatus.VERIFIED,
                service.verify(EMAIL, pending.code()).status());
    }

    @Test
    void rejectsCodeAtExactSixtySecondExpiryBoundary() {
        MutableClock clock = new MutableClock(NOW);
        OtpAuthenticationService service = service(clock);
        var pending = service.prepareIssue(EMAIL);
        service.activate(pending);

        clock.advanceSeconds(60);

        assertEquals(OtpAuthenticationService.VerificationStatus.EXPIRED,
                service.verify(EMAIL, pending.code()).status());
    }

    @Test
    void fifthWrongCodeLocksIssueAndVerificationForFiveMinutes() {
        MutableClock clock = new MutableClock(NOW);
        OtpAuthenticationService service = service(clock);
        var pending = service.prepareIssue(EMAIL);
        service.activate(pending);

        OtpAuthenticationService.VerificationResult fifth = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            fifth = service.verify(EMAIL, "000000".equals(pending.code()) ? "000001" : "000000");
        }
        assertEquals(OtpAuthenticationService.VerificationStatus.LOCKED, fifth.status());
        assertEquals(300, fifth.retryAfterSeconds());
        assertEquals(OtpAuthenticationService.VerificationStatus.LOCKED, service.verify(EMAIL, pending.code()).status());
        assertEquals(300, assertThrows(OtpAuthenticationException.class,
                () -> service.prepareIssue(EMAIL)).retryAfterSeconds());

        clock.advanceSeconds(299);
        assertEquals(OtpAuthenticationException.Reason.LOCKED,
                assertThrows(OtpAuthenticationException.class,
                        () -> service.prepareIssue(EMAIL)).reason());
        clock.advanceSeconds(1);
        var next = service.prepareIssue(EMAIL);
        service.activate(next);
        assertEquals(OtpAuthenticationService.VerificationStatus.VERIFIED,
                service.verify(EMAIL, next.code()).status());
    }

    @Test
    void resendDoesNotResetFailedAttemptCount() {
        MutableClock clock = new MutableClock(NOW);
        OtpAuthenticationService service = service(clock);
        var first = service.prepareIssue(EMAIL);
        service.activate(first);
        String wrongCode = "000000".equals(first.code()) ? "000001" : "000000";
        assertEquals(OtpAuthenticationService.VerificationStatus.INVALID_CODE,
                service.verify(EMAIL, wrongCode).status());

        clock.advanceSeconds(60);
        var replacement = service.prepareIssue(EMAIL);
        service.activate(replacement);
        for (int attempt = 0; attempt < 3; attempt++) service.verify(EMAIL, wrongCode);
        assertEquals(OtpAuthenticationService.VerificationStatus.LOCKED,
                service.verify(EMAIL, wrongCode).status());
    }

    @Test
    void successfulCodeIsOneTimeAndClearsPriorFailures() {
        MutableClock clock = new MutableClock(NOW);
        OtpAuthenticationService service = service(clock);
        var pending = service.prepareIssue(EMAIL);
        service.activate(pending);
        String wrongCode = "000000".equals(pending.code()) ? "000001" : "000000";
        service.verify(EMAIL, wrongCode);

        assertEquals(OtpAuthenticationService.VerificationStatus.VERIFIED,
                service.verify(EMAIL, pending.code()).status());
        assertEquals(OtpAuthenticationService.VerificationStatus.NOT_ISSUED,
                service.verify(EMAIL, pending.code()).status());

        clock.advanceSeconds(60);
        var next = service.prepareIssue(EMAIL);
        service.activate(next);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertEquals(OtpAuthenticationService.VerificationStatus.INVALID_CODE,
                    service.verify(EMAIL, wrongCode).status());
        }
        assertEquals(OtpAuthenticationService.VerificationStatus.LOCKED,
                service.verify(EMAIL, wrongCode).status());
    }

    @Test
    void emailCooldownIsSixtySecondsAndUnsentChallengeCanBeCancelled() {
        MutableClock clock = new MutableClock(NOW);
        OtpAuthenticationService service = service(clock);
        var pending = service.prepareIssue(EMAIL);
        service.activate(pending);
        assertEquals(OtpAuthenticationException.Reason.COOLDOWN,
                assertThrows(OtpAuthenticationException.class,
                        () -> service.prepareIssue(EMAIL)).reason());
        clock.advanceSeconds(60);
        var replacement = service.prepareIssue(EMAIL);
        service.cancel(replacement);
        assertTrue(service.prepareIssue(EMAIL).code().matches("\\d{6}"));
    }

    private static OtpAuthenticationService service(Clock clock) {
        return new OtpAuthenticationService(new InMemoryOtpChallengeStore(),
                new OtpCodeHasher("test-secret-that-is-at-least-32-bytes"), clock);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        private void advanceSeconds(long seconds) { now.updateAndGet(current -> current.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
