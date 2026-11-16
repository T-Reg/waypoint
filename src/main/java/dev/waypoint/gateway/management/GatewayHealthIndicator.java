package dev.waypoint.gateway.management;

import dev.waypoint.gateway.identity.KeyService;
import dev.waypoint.gateway.store.GatewayState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness: up only once bootstrapped, not draining, and our key heartbeat is fresh enough that
 * peers still publish it. A DB outage alone doesn't fail readiness; it shows as configStore DOWN.
 */
public class GatewayHealthIndicator implements HealthIndicator {

    private final GatewayState state;
    private final KeyService keys;

    public GatewayHealthIndicator(GatewayState state, KeyService keys) {
        this.state = state;
        this.keys = keys;
    }

    public boolean isReady() {
        return state.isBootstrapped() && !state.isDraining() && keys.heartbeatFresh();
    }

    @Override
    public Health health() {
        return (isReady() ? Health.up() : Health.outOfService())
                .withDetail("configStore", state.isConfigStoreUp() ? "UP" : "DOWN")
                .build();
    }
}
