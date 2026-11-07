package com.meetingautomation.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthenticatedSessionStoreTests {
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void keepsConcurrentDeviceSessionsAndRevokesOnlyTheSelectedCookie() {
        var store = new InMemoryAuthenticatedSessionStore();
        var first = store.create("User@example.com", NOW);
        var second = store.create("user@example.com", NOW);

        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(first.cookieToken()).isNotEqualTo(first.sessionId().toString());
        assertThat(store.find(first.cookieToken(), NOW)).isPresent();
        assertThat(store.find(second.cookieToken(), NOW)).isPresent();
        assertThat(store.revoke(first.cookieToken())).isTrue();
        assertThat(store.find(first.cookieToken(), NOW)).isEmpty();
        assertThat(store.find(second.cookieToken(), NOW)).isPresent();
    }

    @Test
    void expiresAtTwoHoursAndDoesNotReviveAnExpiredSessionWithLateActivity() {
        var store = new InMemoryAuthenticatedSessionStore();
        var credentials = store.create("user@example.com", NOW);
        Instant expired = NOW.plusSeconds(7_200);

        assertThat(store.find(credentials.cookieToken(), expired)).isEmpty();
        assertThat(store.recordActivity(credentials.cookieToken(), expired)).isEmpty();
    }

    @Test
    void statusLookupDoesNotRefreshButExplicitActivityDoes() {
        var store = new InMemoryAuthenticatedSessionStore();
        var credentials = store.create("user@example.com", NOW);
        Instant afterOneHour = NOW.plusSeconds(3_600);

        assertThat(store.find(credentials.cookieToken(), afterOneHour).orElseThrow().lastActivityAt()).isEqualTo(NOW);
        assertThat(store.recordActivity(credentials.cookieToken(), afterOneHour).orElseThrow().lastActivityAt())
                .isEqualTo(afterOneHour);
        assertThat(store.find(credentials.cookieToken(), afterOneHour.plusSeconds(7_199))).isPresent();
        assertThat(store.find(credentials.cookieToken(), afterOneHour.plusSeconds(7_200))).isEmpty();
    }
}
