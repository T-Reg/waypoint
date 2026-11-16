package dev.waypoint.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LocalRateLimiterTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final LocalRateLimiter limiter = new LocalRateLimiter(clock);

    @Test
    void burstsUpToLimitThenDeniesWithAccurateRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("acme", 3, "r").allowed()).isTrue();
            clock.advance(Duration.ofSeconds(10));
        }
        RateDecision denied = limiter.tryAcquire("acme", 3, "r");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMs()).isEqualTo(30_000);
        assertThat(denied.retryAfterSeconds()).isEqualTo(30);
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire("acme", 3, "r").allowed()).isTrue();
    }

    @Test
    void tenantsAreIndependent() {
        assertThat(limiter.tryAcquire("acme", 1, "r").allowed()).isTrue();
        assertThat(limiter.tryAcquire("acme", 1, "r").allowed()).isFalse();
        assertThat(limiter.tryAcquire("globex", 1, "r").allowed()).isTrue();
    }

    @Test
    void shrinkingLimitTakesEffectImmediately() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("acme", 10, "r");
        }
        assertThat(limiter.tryAcquire("acme", 2, "r").allowed()).isFalse();
    }

    @Test
    void retryAfterRoundsUp() {
        assertThat(RateDecision.deny(1).retryAfterSeconds()).isEqualTo(1);
        assertThat(RateDecision.deny(1001).retryAfterSeconds()).isEqualTo(2);
        assertThat(RateDecision.allow().allowed()).isTrue();
    }
}
