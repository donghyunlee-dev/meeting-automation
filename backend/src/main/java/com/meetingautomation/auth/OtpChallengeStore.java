package com.meetingautomation.auth;

import java.time.Instant;
import java.util.UUID;

public interface OtpChallengeStore {
    void prepare(String email, UUID requestId, byte[] verifier, Instant now);
    Instant activate(String email, UUID requestId, Instant now);
    void cancel(String email, UUID requestId);
    OtpAuthenticationService.VerificationResult verify(String email, byte[] candidateVerifier, Instant now);
}
