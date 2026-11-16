package dev.waypoint.gateway.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** One audit line. Never contains tokens, query strings or bodies; {@code path} only on tenant opt-in. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder(alphabetic = false)
public record AuditEntry(
        String ts,
        String requestId,
        String listener,
        String tenantId,
        String sub,
        String method,
        String pathPrefix,
        String pathHmac,
        @JsonInclude(JsonInclude.Include.NON_NULL) String path,
        String routeId,
        String upstream,
        Decision decision,
        String denyCode,
        String errorCode,
        int status,
        Integer upstreamStatus,
        long latencyMs,
        String clientIp) {

    public enum Decision { ALLOW, DENY }
}
