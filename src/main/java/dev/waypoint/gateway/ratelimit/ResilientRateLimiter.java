package dev.waypoint.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;

/**
 * Degrade, don't fail: if the shared limiter errors or times out, enforce RPM ÷ live instances
 * locally, then probe the shared limiter again after {@code probeInterval} (a simple circuit breaker).
 */
public class ResilientRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);
    static final int WARM_UP_ATTEMPTS = 3;

    private final RateLimiter shared;
    private final RateLimiter fallback;
    private final IntSupplier liveInstances;
    private final Clock clock;
    private final Duration probeInterval;
    private volatile Instant nextProbe;

    public ResilientRateLimiter(RateLimiter shared, RateLimiter fallback, IntSupplier liveInstances, Clock clock, Duration probeInterval) {
        this.shared = shared;
        this.fallback = fallback;
        this.liveInstances = liveInstances;
        this.clock = clock;
        this.probeInterval = probeInterval;
    }

    @Override
    public RateDecision tryAcquire(String tenantId, int requestsPerMinute, String requestId) {
        Instant probe = nextProbe;
        if (probe == null || !clock.instant().isBefore(probe)) {
            try {
                RateDecision decision = shared.tryAcquire(tenantId, requestsPerMinute, requestId);
                if (nextProbe != null) {
                    log.info("Shared rate limiter recovered; leaving degraded mode");
                    nextProbe = null;
                }
                return decision;
            } catch (RuntimeException e) {
                if (nextProbe == null) {
                    log.warn("Shared rate limiter unavailable; degrading to local quota (RPM / live instances): {}", e.getMessage());
                }
                nextProbe = clock.instant().plus(probeInterval);
            }
        }
        int share = Math.max(1, requestsPerMinute / Math.max(1, liveInstances.getAsInt()));
        return fallback.tryAcquire(tenantId, share, requestId);
    }

    /**
     * A cold first connection (DNS, TLS, client init) can exceed the connect timeout; retrying runs warm.
     * If the shared limiter stays unreachable, start degraded rather than stall the first requests.
     */
    @Override
    public void warmUp() {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < WARM_UP_ATTEMPTS; attempt++) {
            try {
                shared.warmUp();
                nextProbe = null;
                return;
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        log.warn("Shared rate limiter unreachable at startup; starting degraded (RPM / live instances): {}",
                NestedExceptionUtils.getMostSpecificCause(failure).getMessage());
        nextProbe = clock.instant().plus(probeInterval);
    }

    /** A failed ping doesn't degrade: the client reconnects in the background, and requests still probe as usual. */
    @Override
    public void keepAlive() {
        try {
            shared.keepAlive();
        } catch (RuntimeException e) {
            log.debug("Shared rate limiter keep-alive failed: {}", e.getMessage());
        }
    }

    public boolean isDegraded() {
        return nextProbe != null;
    }
}
