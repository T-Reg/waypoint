package dev.waypoint.visualizer.traffic;

import java.time.Instant;
import java.util.Map;

/** What happened to one request, including what (if anything) reached the upstream. */
public record RequestResult(
        long seq,
        String runId,
        String scenarioId,
        Instant ts,
        String target,
        String servedBy,
        String method,
        String path,
        String tenant,
        String fault,
        int status,
        Outcome outcome,
        Stage stage,
        String code,
        String detail,
        String retryAfter,
        String allow,
        String requestId,
        long latencyMs,
        Map<String, String> responseHeaders,
        String responseBody,
        Upstream upstream) {

    public enum Outcome { ALLOWED, REJECTED, ERROR }

    /** {@code checked=false} when the upstream's request journal could not be queried. */
    public record Upstream(boolean checked, boolean received, String url, Map<String, String> headers, Identity identity) {

        public static Upstream unchecked() {
            return new Upstream(false, false, null, Map.of(), null);
        }

        public static Upstream notReceived() {
            return new Upstream(true, false, null, Map.of(), null);
        }
    }

    /** The X-Gateway-Identity token the upstream received, verified against the gateway's JWKS. */
    public record Identity(boolean verified, String kid, String algorithm, Map<String, Object> claims, String error) {
    }
}
