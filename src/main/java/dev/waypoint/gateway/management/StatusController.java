package dev.waypoint.gateway.management;

import dev.waypoint.gateway.identity.KeyService;
import dev.waypoint.gateway.ratelimit.ResilientRateLimiter;
import dev.waypoint.gateway.ratelimit.RateLimiter;
import dev.waypoint.gateway.store.GatewayState;
import dev.waypoint.gateway.store.SnapshotHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Convergence view for operators and tests: revision, key generation, LISTEN state, limiter mode. */
@RestController
public class StatusController {

    private final String instanceId;
    private final SnapshotHolder snapshots;
    private final GatewayState state;
    private final KeyService keys;
    private final RateLimiter rateLimiter;
    private final GatewayHealthIndicator readiness;

    public StatusController(GatewayInstance instance, SnapshotHolder snapshots, GatewayState state, KeyService keys,
                            RateLimiter rateLimiter, GatewayHealthIndicator readiness) {
        this.instanceId = instance.id();
        this.snapshots = snapshots;
        this.state = state;
        this.keys = keys;
        this.rateLimiter = rateLimiter;
        this.readiness = readiness;
    }

    @GetMapping("/internal/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("instanceId", instanceId);
        status.put("ready", readiness.isReady());
        status.put("revision", snapshots.get().revision());
        status.put("invalidEntries", snapshots.get().invalidEntries());
        status.put("keyGeneration", keys.appliedGeneration());
        status.put("kid", keys.currentKid());
        status.put("configStore", state.isConfigStoreUp() ? "UP" : "DOWN");
        status.put("listenConnected", state.isListenConnected());
        status.put("listenConnections", state.listenConnections());
        status.put("liveInstances", keys.liveInstances());
        status.put("rateLimiter", rateLimiter instanceof ResilientRateLimiter r && r.isDegraded() ? "degraded" : "normal");
        return status;
    }
}
