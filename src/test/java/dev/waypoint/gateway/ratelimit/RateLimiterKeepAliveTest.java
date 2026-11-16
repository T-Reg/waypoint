package dev.waypoint.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterKeepAliveTest {

    private final AtomicInteger pings = new AtomicInteger();
    private final RateLimiter limiter = new RateLimiter() {
        @Override
        public RateDecision tryAcquire(String tenantId, int requestsPerMinute, String requestId) {
            return RateDecision.allow();
        }

        @Override
        public void keepAlive() {
            pings.incrementAndGet();
        }
    };

    @Test
    void pingsRepeatedlyWhileRunningAndStopsOnStop() {
        RateLimiterKeepAlive keepAlive = new RateLimiterKeepAlive(limiter, Duration.ofMillis(20));
        assertThat(keepAlive.isRunning()).isFalse();

        keepAlive.start();
        keepAlive.start();
        assertThat(keepAlive.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> pings.get() >= 3);

        keepAlive.stop();
        assertThat(keepAlive.isRunning()).isFalse();
        int afterStop = pings.get();
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> pings.get() == afterStop);
        keepAlive.stop();
    }

    @Test
    void localLimiterKeepAliveIsANoOp() {
        new LocalRateLimiter(Clock.systemUTC()).keepAlive();
    }
}
