package com.meetingautomation.api.auth;

import com.meetingautomation.auth.OtpAuthenticationException;
import com.meetingautomation.auth.OtpAuthenticationService;
import com.meetingautomation.auth.OtpEmailSender;
import com.meetingautomation.auth.AuthenticatedSessionStore;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public final class AuthController {
    private static final String COOKIE_NAME = "ma_session";
    private static final long IDLE_SECONDS = 7_200;
    private final OtpAuthenticationService otp;
    private final OtpEmailSender emailSender;
    private final AuthenticatedSessionStore sessions;
    private final Clock clock;
    private final boolean secureCookie;
    private final String cookiePath;

    @Autowired
    public AuthController(OtpAuthenticationService otp, OtpEmailSender emailSender,
            AuthenticatedSessionStore sessions, @Value("${AUTH_COOKIE_SECURE:false}") boolean secureCookie,
            @Value("${AUTH_COOKIE_PATH:/api/v1/auth}") String cookiePath) {
        this(otp, emailSender, sessions, Clock.systemUTC(), secureCookie, cookiePath);
    }

    AuthController(OtpAuthenticationService otp, OtpEmailSender emailSender) {
        this(otp, emailSender, new com.meetingautomation.auth.InMemoryAuthenticatedSessionStore(),
                Clock.systemUTC(), false, "/api/v1/auth");
    }

    AuthController(OtpAuthenticationService otp, OtpEmailSender emailSender, AuthenticatedSessionStore sessions,
            Clock clock, boolean secureCookie, String cookiePath) {
        this.otp = otp;
        this.emailSender = emailSender;
        this.sessions = sessions;
        this.clock = clock;
        this.secureCookie = secureCookie;
        this.cookiePath = cookiePath;
    }

    @PostMapping("/otp-requests")
    public ResponseEntity<Map<String, Object>> requestOtp(@Valid @RequestBody OtpRequest request) {
        OtpAuthenticationService.PendingOtp pending;
        try {
            pending = otp.prepareIssue(request.email());
        } catch (OtpAuthenticationException throttled) {
            return throttled(throttled.retryAfterSeconds());
        } catch (IllegalStateException notConfigured) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "AUTH_UNAVAILABLE"));
        }
        boolean sent;
        try {
            sent = emailSender.sendOtpEmail(pending.email(), pending.code());
        } catch (RuntimeException deliveryFailure) {
            sent = false;
        }
        if (!sent) {
            otp.cancel(pending);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "EMAIL_DELIVERY_FAILED"));
        }
        otp.activate(pending);
        return ResponseEntity.accepted().body(Map.of("data", Map.of("expiresInSeconds", 60,
                "resendAfterSeconds", 60)));
    }

    @PostMapping("/otp-verifications")
    public ResponseEntity<Map<String, Object>> verifyOtp(@Valid @RequestBody OtpVerificationRequest request) {
        var result = otp.verify(request.email(), request.code());
        return switch (result.status()) {
            case VERIFIED -> {
                var credentials = sessions.create(request.email(), clock.instant());
                yield ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie(credentials.cookieToken()).toString())
                        .body(Map.of("data", Map.of("verified", true,
                                "sessionId", credentials.sessionId().toString())));
            }
            case LOCKED -> throttled(result.retryAfterSeconds());
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("data", Map.of("verified", false, "reason", "EXPIRED")));
            case INVALID_CODE, NOT_ISSUED -> ResponseEntity.badRequest()
                    .body(Map.of("data", Map.of("verified", false)));
        };
    }

    @GetMapping("/session")
    public ResponseEntity<Map<String, Object>> session(@CookieValue(name = COOKIE_NAME, required = false) String token) {
        var session = sessions.find(token, clock.instant());
        if (session.isEmpty()) return unauthorized();
        long elapsed = Duration.between(session.get().lastActivityAt(), clock.instant()).getSeconds();
        long remaining = IDLE_SECONDS - elapsed;
        return ResponseEntity.ok(Map.<String, Object>of("data", Map.of("authenticated", true,
                "sessionId", session.get().sessionId().toString(), "remainingIdleSeconds", remaining)));
    }

    @PostMapping("/session/activity")
    public ResponseEntity<Map<String, Object>> activity(
            @CookieValue(name = COOKIE_NAME, required = false) String token) {
        Instant now = clock.instant();
        var session = sessions.recordActivity(token, now);
        if (session.isEmpty()) return unauthorized();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie(token).toString())
                .body(Map.<String, Object>of("data", Map.of("authenticated", true, "remainingIdleSeconds", IDLE_SECONDS)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @CookieValue(name = COOKIE_NAME, required = false) String token) {
        sessions.revoke(token);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .body(Map.of("data", Map.of("loggedOut", true)));
    }

    private ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(COOKIE_NAME, token).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path(cookiePath).maxAge(IDLE_SECONDS).build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(COOKIE_NAME, "").httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path(cookiePath).maxAge(0).build();
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("data", Map.of("authenticated", false)));
    }

    private static ResponseEntity<Map<String, Object>> throttled(int seconds) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", Integer.toString(seconds))
                .body(Map.of("error", "RATE_LIMITED", "retryAfterSeconds", seconds));
    }
}
