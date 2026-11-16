package dev.waypoint.gateway.ratelimit;

import java.time.Duration;

/** Per-tenant quota: at most {@code requestsPerMinute} admissions in any 60 s window. */
public interface RateLimiter {

    Duration WINDOW = Duration.ofMinutes(1);

    RateDecision tryAcquire(String tenantId, int requestsPerMinute, String requestId);

    /** Opens any connection the limiter needs before the instance is ready, so requests never pay for it. */
    default void warmUp() {}

    /** Called periodically off the request path so an idle connection is never dropped or found dead by a request. */
    default void keepAlive() {}
}
