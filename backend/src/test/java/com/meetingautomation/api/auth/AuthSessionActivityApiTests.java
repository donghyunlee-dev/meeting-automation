package com.meetingautomation.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meetingautomation.auth.InMemoryAuthenticatedSessionStore;
import com.meetingautomation.auth.InMemoryOtpChallengeStore;
import com.meetingautomation.auth.OtpAuthenticationService;
import com.meetingautomation.auth.OtpCodeHasher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthSessionActivityApiTests {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void statusDoesNotExtendIdleWindowButActivityRefreshesCookie() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        var sessions = new InMemoryAuthenticatedSessionStore();
        var otp = new OtpAuthenticationService(new InMemoryOtpChallengeStore(),
                new OtpCodeHasher("test-secret-that-is-at-least-32-bytes"), clock);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(otp, (email, code) -> true,
                sessions, clock, true, "/api/backend/api/v1/auth")).build();
        var pending = otp.prepareIssue("user@example.com");
        otp.activate(pending);
        String cookie = mvc.perform(post("/api/v1/auth/otp-verifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"code\":\"" + pending.code() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("ma_session").getValue();

        clock.now = NOW.plusSeconds(3_600);
        mvc.perform(get("/api/v1/auth/session").cookie(new jakarta.servlet.http.Cookie("ma_session", cookie)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingIdleSeconds").value(3600));
        clock.now = NOW.plusSeconds(7_199);
        mvc.perform(get("/api/v1/auth/session").cookie(new jakarta.servlet.http.Cookie("ma_session", cookie)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingIdleSeconds").value(1));
        clock.now = NOW.plusSeconds(7_200);
        mvc.perform(get("/api/v1/auth/session").cookie(new jakarta.servlet.http.Cookie("ma_session", cookie)))
                .andExpect(status().isUnauthorized());
        assertThat(sessions.find(cookie, clock.instant())).isEmpty();
    }

    @Test
    void authenticatedActivityRefreshesOnlyAuthCookiePathAndCannotReviveExpiration() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        var sessions = new InMemoryAuthenticatedSessionStore();
        var credentials = sessions.create("user@example.com", NOW);
        var otp = new OtpAuthenticationService(new InMemoryOtpChallengeStore(),
                new OtpCodeHasher("test-secret-that-is-at-least-32-bytes"), clock);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(otp, (email, code) -> true,
                sessions, clock, true, "/api/backend/api/v1/auth")).build();

        clock.now = NOW.plusSeconds(60);
        var response = mvc.perform(post("/api/v1/auth/session/activity")
                        .cookie(new jakarta.servlet.http.Cookie("ma_session", credentials.cookieToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remainingIdleSeconds").value(7200))
                .andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly", "Secure", "SameSite=Strict",
                "Path=/api/backend/api/v1/auth", "Max-Age=7200");

        clock.now = NOW.plusSeconds(60 + 7200);
        mvc.perform(post("/api/v1/auth/session/activity")
                        .cookie(new jakarta.servlet.http.Cookie("ma_session", credentials.cookieToken())))
                .andExpect(status().isUnauthorized());
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
