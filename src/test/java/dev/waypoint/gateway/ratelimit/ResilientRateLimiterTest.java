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

    @Test
    void warmUpRetriesAColdConnectAndStaysShared() {
        AtomicInteger warmUpCalls = new AtomicInteger();
        ResilientRateLimiter warmed = new ResilientRateLimiter(warmingShared(warmUpCalls, 1), new LocalRateLimiter(clock), () -> 3,
                clock, Duration.ofSeconds(1));
        warmed.warmUp();
        assertThat(warmUpCalls.get()).isEqualTo(2);
        assertThat(warmed.isDegraded()).isFalse();
    }

    @Test
    void warmUpThatNeverConnectsStartsDegradedAndProbesLater() {
        AtomicInteger warmUpCalls = new AtomicInteger();
        ResilientRateLimiter cold = new ResilientRateLimiter(warmingShared(warmUpCalls, Integer.MAX_VALUE), new LocalRateLimiter(clock),
                () -> 3, clock, Duration.ofSeconds(1));
        cold.warmUp();
        assertThat(warmUpCalls.get()).isEqualTo(ResilientRateLimiter.WARM_UP_ATTEMPTS);
        assertThat(cold.isDegraded()).isTrue();

        assertThat(cold.tryAcquire("acme", 60, "r").allowed()).isTrue();
        assertThat(sharedCalls.get()).as("no request waits on the shared limiter before the probe interval").isZero();

        clock.advance(Duration.ofSeconds(1));
        cold.tryAcquire("acme", 60, "r");
        assertThat(sharedCalls.get()).isEqualTo(1);
        assertThat(cold.isDegraded()).isFalse();
    }

    @Test
    void keepAlivePingsTheSharedLimiter() {
        AtomicInteger pings = new AtomicInteger();
        ResilientRateLimiter alive = new ResilientRateLimiter(pingingShared(pings, false), new LocalRateLimiter(clock), () -> 3,
                clock, Duration.ofSeconds(1));
        alive.keepAlive();
        alive.keepAlive();
        assertThat(pings.get()).isEqualTo(2);
        assertThat(alive.isDegraded()).isFalse();
    }

    @Test
    void failedKeepAliveNeitherThrowsNorDegrades() {
        AtomicInteger pings = new AtomicInteger();
        ResilientRateLimiter dead = new ResilientRateLimiter(pingingShared(pings, true), new LocalRateLimiter(clock), () -> 3,
                clock, Duration.ofSeconds(1));
        dead.keepAlive();
        assertThat(pings.get()).isEqualTo(1);
        assertThat(dead.isDegraded()).as("requests keep using the shared limiter").isFalse();
        dead.tryAcquire("acme", 60, "r");
        assertThat(sharedCalls.get()).isEqualTo(1);
    }

    private RateLimiter pingingShared(AtomicInteger pings, boolean fail) {
        return new RateLimiter() {
            @Override
            public RateDecision tryAcquire(String tenantId, int requestsPerMinute, String requestId) {
                return shared.tryAcquire(tenantId, requestsPerMinute, requestId);
            }

            @Override
            public void keepAlive() {
                pings.incrementAndGet();
                if (fail) {
                    throw new IllegalStateException("Redis command timed out");
                }
            }
        };
    }

    /** The shared limiter's warm-up fails {@code failures} times, then succeeds. */
    private RateLimiter warmingShared(AtomicInteger warmUpCalls, int failures) {
        return new RateLimiter() {
            @Override
            public RateDecision tryAcquire(String tenantId, int requestsPerMinute, String requestId) {
                return shared.tryAcquire(tenantId, requestsPerMinute, requestId);
            }

            @Override
            public void warmUp() {
                if (warmUpCalls.incrementAndGet() <= failures) {
                    throw new IllegalStateException("connect timed out");
                }
            }
        };
    }
}
