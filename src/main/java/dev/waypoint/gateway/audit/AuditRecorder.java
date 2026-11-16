package dev.waypoint.gateway.audit;

import dev.waypoint.gateway.pipeline.RequestContext;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds audit entries; the raw path appears only for tenants with {@code auditRawPath: true}. */
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    public record AdminRequest(String requestId, String method, String path, String actor, int status,
                               String denyCode, long startNanos, String clientIp) {}

    private final AuditSink sink;
    private final PathHasher hasher;
    private final Clock clock;

    public AuditRecorder(AuditSink sink, PathHasher hasher, Clock clock) {
        this.sink = sink;
        this.hasher = hasher;
        this.clock = clock;
    }

    public void recordPublic(RequestContext ctx, int status) {
        try {
            String path = ctx.normalizedPath();
            String hashed = path != null ? path : ctx.request().getRequestURI();
            boolean rawPath = path != null && ctx.tenant() != null && ctx.tenant().auditRawPath();
            sink.write(new AuditEntry(
                    clock.instant().toString(),
                    ctx.requestId(),
                    "public",
                    ctx.identity() != null ? ctx.identity().tenantId() : null,
                    ctx.identity() != null ? ctx.identity().subject() : null,
                    ctx.method(),
                    ctx.route() != null ? ctx.route().pathPrefix() : null,
                    hashed != null ? hasher.hash(hashed) : null,
                    rawPath ? path : null,
                    ctx.route() != null ? ctx.route().id() : null,
                    ctx.upstreamOrigin(),
                    ctx.decision(),
                    ctx.denyCode(),
                    ctx.errorCode(),
                    status,
                    ctx.upstreamStatus(),
                    elapsedMs(ctx.startNanos()),
                    ctx.client().clientIp()));
        } catch (RuntimeException e) {
            log.error("Failed to write audit entry for {}", ctx.requestId(), e);
        }
    }

    public void recordAdmin(AdminRequest request) {
        try {
            boolean denied = request.denyCode() != null || request.status() >= 400;
            sink.write(new AuditEntry(
                    clock.instant().toString(),
                    request.requestId(),
                    "admin",
                    null,
                    request.actor(),
                    request.method(),
                    null,
                    hasher.hash(request.path()),
                    request.path(),
                    null,
                    null,
                    denied ? AuditEntry.Decision.DENY : AuditEntry.Decision.ALLOW,
                    request.denyCode(),
                    null,
                    request.status(),
                    null,
                    elapsedMs(request.startNanos()),
                    request.clientIp()));
        } catch (RuntimeException e) {
            log.error("Failed to write admin audit entry for {}", request.requestId(), e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
