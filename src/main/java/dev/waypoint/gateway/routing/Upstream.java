package dev.waypoint.gateway.routing;

/** {@code weight} is reserved for canary routing via {@link UpstreamSelector}. */
public record Upstream(String url, Integer weight) {}
