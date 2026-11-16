package dev.waypoint.visualizer.traffic;

import dev.waypoint.visualizer.traffic.RequestResult.Outcome;
import java.util.Map;

/**
 * Works out where a request stopped from what the gateway returned. Every gateway rejection carries a
 * problem+json {@code code} that belongs to exactly one pipeline stage (docs/design.md §6).
 */
public final class StageMapper {

    private static final Map<String, Stage> CODES = Map.ofEntries(
            Map.entry("gateway.not_ready", Stage.LISTENER),
            Map.entry("request.too_large", Stage.SIZE),
            Map.entry("request.bad_path", Stage.NORMALIZE),
            Map.entry("auth.missing", Stage.AUTHENTICATE),
            Map.entry("auth.invalid", Stage.AUTHENTICATE),
            Map.entry("auth.expired", Stage.AUTHENTICATE),
            Map.entry("auth.unknown_tenant", Stage.AUTHENTICATE),
            Map.entry("policy.path_denied", Stage.ALLOWLIST),
            Map.entry("route.not_found", Stage.ROUTE),
            Map.entry("route.unavailable", Stage.ROUTE),
            Map.entry("policy.method_denied", Stage.METHOD),
            Map.entry("policy.scope_missing", Stage.SCOPE),
            Map.entry("policy.quota_exceeded", Stage.QUOTA),
            Map.entry("upstream.unavailable", Stage.PROXY),
            Map.entry("upstream.timeout", Stage.PROXY),
            Map.entry("internal", Stage.PROXY));

    private StageMapper() {
    }

    public record Placement(Stage stage, Outcome outcome) {
    }

    public static boolean isGatewayCode(String code) {
        return code != null && CODES.containsKey(code);
    }

    /** Where a request that never got an HTTP response stopped: the first hop it tried to reach. */
    public static Placement unreachable(boolean viaLoadBalancer) {
        return new Placement(viaLoadBalancer ? Stage.LOAD_BALANCER : Stage.LISTENER, Outcome.ERROR);
    }

    public static Placement place(int status, String gatewayCode, RequestResult.Upstream upstream, boolean viaLoadBalancer) {
        if (isGatewayCode(gatewayCode)) {
            return new Placement(CODES.get(gatewayCode), status >= 500 ? Outcome.ERROR : Outcome.REJECTED);
        }
        if (upstream.received() || (!upstream.checked() && status < 500)) {
            return new Placement(Stage.UPSTREAM, Outcome.ALLOWED);
        }
        if (status >= 500) {
            return new Placement(viaLoadBalancer ? Stage.LOAD_BALANCER : Stage.LISTENER, Outcome.ERROR);
        }
        // A 4xx without a gateway code that never reached the upstream: Tomcat rejected it before the pipeline.
        return new Placement(Stage.LISTENER, Outcome.REJECTED);
    }
}
