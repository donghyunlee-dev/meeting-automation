package com.meetingautomation.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.auth.InMemoryOtpChallengeStore;
import com.meetingautomation.auth.OtpAuthenticationService;
import com.meetingautomation.auth.OtpCodeHasher;
import com.meetingautomation.auth.OtpEmailSender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTests {
    private static final String EMAIL = "leedh84@gmail.com";
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void sendsSixDigitCodeAndReturnsExpiryWithoutEchoingCodeOrEmail() throws Exception {
        AtomicReference<String> sentEmail = new AtomicReference<>();
        AtomicReference<String> sentCode = new AtomicReference<>();
        MockMvc mvc = mvc((email, code) -> {
            sentEmail.set(email);
            sentCode.set(code);
            return true;
        });

        mvc.perform(post("/api/v1/auth/otp-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" " + EMAIL + " \"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(60))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.code").doesNotExist())
                .andExpect(jsonPath("$.data.email").doesNotExist());

        assertThat(sentEmail.get()).isEqualTo(EMAIL);
        assertThat(sentCode.get()).matches("\\d{6}");
    }

    @Test
    void rejectsInvalidEmailBeforeSending() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        mvc((email, code) -> { sends.incrementAndGet(); return true; })
                .perform(post("/api/v1/auth/otp-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
        assertThat(sends.get()).isZero();
    }

    @Test
    void failedMailDeliveryLeavesNoVerifiableChallenge() throws Exception {
        MockMvc mvc = mvc((email, code) -> false);
        mvc.perform(post("/api/v1/auth/otp-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isBadGateway());
        mvc.perform(verify(EMAIL, "123456"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.verified").value(false));
    }

    @Test
    void enforcesSixtySecondPerEmailSendCooldown() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        MockMvc mvc = mvc((email, code) -> { sends.incrementAndGet(); return true; });
        mvc.perform(post("/api/v1/auth/otp-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/auth/otp-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("60"));
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test
    void countsWrongCodesAndLocksAfterFiveFailures() throws Exception {
        AtomicReference<String> sentCode = new AtomicReference<>();
        MockMvc mvc = mvc((email, code) -> { sentCode.set(code); return true; });
        mvc.perform(post("/api/v1/auth/otp-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isAccepted());
        String wrong = "000000".equals(sentCode.get()) ? "000001" : "000000";
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(verify(EMAIL, wrong)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.verified").value(false));
        }
        mvc.perform(verify(EMAIL, wrong)).andExpect(status().isTooManyRequests())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("300"));
        mvc.perform(post("/api/v1/auth/otp-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulVerificationCreatesAnIndependentHttpOnlySessionCookie() throws Exception {
        AtomicReference<String> sentCode = new AtomicReference<>();
        MockMvc mvc = mvc((email, code) -> { sentCode.set(code); return true; });
        mvc.perform(post("/api/v1/auth/otp-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isAccepted());

        mvc.perform(verify(EMAIL, sentCode.get()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.sessionId").exists())
                .andExpect(cookie().exists("ma_session"))
                .andExpect(cookie().httpOnly("ma_session", true));
    }

    private static MockMvc mvc(OtpEmailSender sender) {
        var service = new OtpAuthenticationService(new InMemoryOtpChallengeStore(),
                new OtpCodeHasher("test-secret-that-is-at-least-32-bytes"), Clock.fixed(NOW, ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(new AuthController(service, sender)).build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verify(String email, String code) {
        return post("/api/v1/auth/otp-verifications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}");
    }
}
