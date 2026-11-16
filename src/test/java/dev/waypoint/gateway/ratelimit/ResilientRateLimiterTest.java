package dev.waypoint.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.support.MutableClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilientRateLimiterTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final AtomicBoolean sharedUp = new AtomicBoolean(true);
    private final AtomicInteger sharedCalls = new AtomicInteger();
    private final RateLimiter shared = (tenant, rpm, id) -> {
        sharedCalls.incrementAndGet();
        if (!sharedUp.get()) {
            throw new IllegalStateException("valkey down");
        }
        return RateDecision.allow();
    };
    private final ResilientRateLimiter limiter = new ResilientRateLimiter(shared, new LocalRateLimiter(clock), () -> 3, clock, Duration.ofSeconds(1));

    @Test
    void usesSharedLimiterWhenHealthy() {
        assertThat(limiter.tryAcquire("acme", 60, "r").allowed()).isTrue();
        assertThat(limiter.isDegraded()).isFalse();
    }

    @Test
    void degradesToLocalShareThenProbesAndRecovers() {
        sharedUp.set(false);
        int allowed = 0;
        for (int i = 0; i < 30; i++) {
            if (limiter.tryAcquire("acme", 60, "r").allowed()) {
                allowed++;
            }
        }
        assertThat(limiter.isDegraded()).isTrue();
        assertThat(allowed).isEqualTo(20);
        assertThat(sharedCalls.get()).as("no probe before the interval").isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        limiter.tryAcquire("acme", 60, "r");
        assertThat(sharedCalls.get()).as("one probe after the interval").isEqualTo(2);
        assertThat(limiter.isDegraded()).isTrue();

        sharedUp.set(true);
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("acme", 60, "r").allowed()).isTrue();
        assertThat(limiter.isDegraded()).isFalse();
    }

    @Test
    void degradedShareIsAtLeastOne() {
        sharedUp.set(false);
        ResilientRateLimiter crowded = new ResilientRateLimiter(shared, new LocalRateLimiter(clock), () -> 100, clock, Duration.ofSeconds(1));
        assertThat(crowded.tryAcquire("acme", 10, "r").allowed()).isTrue();
        assertThat(crowded.tryAcquire("acme", 10, "r").allowed()).isFalse();
    }
}
