package dev.waypoint.gateway.pipeline;

import dev.waypoint.gateway.audit.AuditRecorder;
import dev.waypoint.gateway.error.ProblemWriter;
import dev.waypoint.gateway.error.Rejection;
import dev.waypoint.gateway.proxy.ClientIpResolver;
import dev.waypoint.gateway.proxy.ProxyHandler;
import dev.waypoint.gateway.proxy.ResponseStamp;
import dev.waypoint.gateway.proxy.ResponseAbortedException;
import dev.waypoint.gateway.store.GatewayState;
import dev.waypoint.gateway.store.SnapshotHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Public listener entry point: stages, then proxy; the audit entry is written in {@code finally}. */
public class GatewayPipeline {

    public static final String NOT_READY = "gateway.not_ready";
    public static final String INTERNAL = "internal";

    private static final Logger log = LoggerFactory.getLogger(GatewayPipeline.class);

    private final List<Stage> stages;
    private final ProxyHandler proxy;
    private final SnapshotHolder snapshots;
    private final GatewayState state;
    private final ClientIpResolver clientIpResolver;
    private final ProblemWriter problems;
    private final AuditRecorder audit;
    private final ResponseStamp stamp;

    public GatewayPipeline(List<Stage> stages, ProxyHandler proxy, SnapshotHolder snapshots, GatewayState state,
                           ClientIpResolver clientIpResolver, ProblemWriter problems, AuditRecorder audit,
                           ResponseStamp stamp) {
        this.stages = List.copyOf(stages);
        this.proxy = proxy;
        this.snapshots = snapshots;
        this.state = state;
        this.clientIpResolver = clientIpResolver;
        this.problems = problems;
        this.audit = audit;
        this.stamp = stamp;
    }

    public void handle(HttpServletRequest request, HttpServletResponse response) {
        RequestContext ctx = new RequestContext(UUID.randomUUID().toString(), request, snapshots.get(),
                clientIpResolver.resolve(request.getRemoteAddr(), request.getHeaders("X-Forwarded-For")), System.nanoTime());
        stamp.apply(response, ctx.requestId());
        try {
            Optional<Rejection> rejection = state.isBootstrapped() && snapshots.isLoaded()
                    ? runStages(ctx)
                    : Optional.of(Rejection.of(503, NOT_READY, "gateway is starting"));
            if (rejection.isPresent()) {
                ctx.deny(rejection.get().code());
                problems.write(response, rejection.get(), ctx.requestId());
                return;
            }
            ctx.allow();
            proxy.forward(ctx, response);
        } catch (ResponseAbortedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error handling request {}", ctx.requestId(), e);
            ctx.error(INTERNAL);
            try {
                problems.write(response, Rejection.of(500, INTERNAL, "internal error"), ctx.requestId());
            } catch (Exception ignored) {
                // Response already broken; the audit entry still records the failure.
            }
        } finally {
            audit.recordPublic(ctx, response.getStatus());
        }
    }

    private Optional<Rejection> runStages(RequestContext ctx) {
        for (Stage stage : stages) {
            Optional<Rejection> rejection = stage.apply(ctx);
            if (rejection.isPresent()) {
                return rejection;
            }
        }
        return Optional.empty();
    }
}
