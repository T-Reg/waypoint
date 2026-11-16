package dev.waypoint.visualizer.traffic;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Hops a request passes, in order; matches the gateway pipeline in docs/architecture.md. */
public enum Stage {
    LOAD_BALANCER,
    LISTENER,
    SIZE,
    NORMALIZE,
    AUTHENTICATE,
    ALLOWLIST,
    ROUTE,
    METHOD,
    SCOPE,
    QUOTA,
    PROXY,
    UPSTREAM;

    @JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
