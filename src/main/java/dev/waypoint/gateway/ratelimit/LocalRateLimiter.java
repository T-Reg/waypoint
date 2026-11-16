package dev.waypoint.gateway.ratelimit;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window log, used in tests and as the degraded fallback. Keys are authenticated
 * tenant IDs only, and each log holds at most {@code limit} timestamps, so memory stays bounded.
 */
public class LocalRateLimiter implements RateLimiter {

    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> logs = new ConcurrentHashMap<>();

    public LocalRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RateDecision tryAcquire(String tenantId, int limit, String requestId) {
        long now = clock.millis();
        long windowMs = WINDOW.toMillis();
        ArrayDeque<Long> log = logs.computeIfAbsent(tenantId, id -> new ArrayDeque<>());
        synchronized (log) {
            while (!log.isEmpty() && log.peekFirst() <= now - windowMs) {
                log.pollFirst();
            }
            while (log.size() > limit) {
                log.pollFirst();
            }
            if (log.size() < limit) {
                log.addLast(now);
                return RateDecision.allow();
            }
            return RateDecision.deny(Math.max(1, log.peekFirst() + windowMs - now));
        }
    }
}
