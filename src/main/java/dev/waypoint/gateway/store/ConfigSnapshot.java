package dev.waypoint.gateway.store;

import dev.waypoint.gateway.routing.RouteTable;
import dev.waypoint.gateway.tenant.Tenant;
import java.util.List;
import java.util.Map;

/** Immutable view of routes and tenants at one {@code revision}; requests keep the snapshot they started with. */
public record ConfigSnapshot(long revision, RouteTable routes, Map<String, Tenant> tenants, List<String> invalidEntries) {

    public ConfigSnapshot {
        tenants = Map.copyOf(tenants);
        invalidEntries = List.copyOf(invalidEntries);
    }

    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(-1, RouteTable.empty(), Map.of(), List.of());
    }
}
