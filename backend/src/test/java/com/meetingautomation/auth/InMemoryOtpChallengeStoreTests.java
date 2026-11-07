package com.meetingautomation.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryOtpChallengeStoreTests {
    @Test
    void independentlyIssuedEmailsKeepTheirOwnChallengesAndFailures() {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        OtpAuthenticationService service = new OtpAuthenticationService(new InMemoryOtpChallengeStore(),
                new OtpCodeHasher("test-secret-that-is-at-least-32-bytes"), Clock.fixed(now, ZoneOffset.UTC));
        var ada = service.prepareIssue("ada@example.com");
        var grace = service.prepareIssue("grace@example.com");
        service.activate(ada);
        service.activate(grace);

        String wrongCode = "000000".equals(ada.code()) ? "000001" : "000000";
        assertEquals(OtpAuthenticationService.VerificationStatus.INVALID_CODE,
                service.verify(ada.email(), wrongCode).status());
        assertEquals(OtpAuthenticationService.VerificationStatus.VERIFIED,
                service.verify(grace.email(), grace.code()).status());
        assertEquals(OtpAuthenticationService.VerificationStatus.VERIFIED,
                service.verify(ada.email(), ada.code()).status());
    }
}
