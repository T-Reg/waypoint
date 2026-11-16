package dev.waypoint.gateway.ratelimit;

public record RateDecision(boolean allowed, long retryAfterMs) {

    public static RateDecision allow() {
        return new RateDecision(true, 0);
    }

    public static RateDecision deny(long retryAfterMs) {
        return new RateDecision(false, retryAfterMs);
    }

    /** {@code Retry-After} in whole seconds, rounded up so clients never retry too early. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfterMs + 999) / 1000);
    }
}
