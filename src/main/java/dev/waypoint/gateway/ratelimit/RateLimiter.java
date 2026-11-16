package dev.waypoint.gateway.ratelimit;

import java.time.Duration;

/** Per-tenant quota: at most {@code requestsPerMinute} admissions in any 60 s window. */
public interface RateLimiter {

    Duration WINDOW = Duration.ofMinutes(1);

    RateDecision tryAcquire(String tenantId, int requestsPerMinute, String requestId);
}
