package dev.waypoint.gateway.store;

import tools.jackson.databind.ObjectMapper;
import dev.waypoint.gateway.path.PathPrefix;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.RouteSpec;
import dev.waypoint.gateway.routing.RouteTable;
import dev.waypoint.gateway.routing.RouteValidator;
import dev.waypoint.gateway.tenant.Tenant;
import dev.waypoint.gateway.tenant.TenantSpec;
import dev.waypoint.gateway.tenant.TenantValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates every DB row individually. A bad route keeps its prefix in the table so it fails closed
 * with 503; a bad tenant is dropped (its tokens get 401). One bad row never blocks the rest.
 */
public class SnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBuilder.class);

    private final RouteValidator routeValidator;
    private final ObjectMapper mapper;

    public SnapshotBuilder(RouteValidator routeValidator, ObjectMapper mapper) {
        this.routeValidator = routeValidator;
        this.mapper = mapper;
    }

    public ConfigSnapshot build(PostgresConfigStore.RawSnapshot raw) {
        List<String> invalid = new ArrayList<>();
        RouteTable.Builder routes = RouteTable.builder();
        for (PostgresConfigStore.Row row : raw.routes()) {
            try {
                Route route = routeValidator.validate(row.id(), mapper.readValue(row.specJson(), RouteSpec.class), RouteValidator.Mode.LOAD);
                if (!route.pathPrefix().equals(row.pathPrefix())) {
                    throw new IllegalStateException("pathPrefix column does not match spec");
                }
                routes.add(route);
            } catch (Exception e) {
                log.error("Skipping invalid route '{}' (revision {}); its prefix will return 503: {}", row.id(), raw.revision(), e.getMessage());
                invalid.add("route:" + row.id());
                if (PathPrefix.isValid(row.pathPrefix())) {
                    routes.addUnavailable(row.id(), row.pathPrefix());
                }
            }
        }
        Map<String, Tenant> tenants = new HashMap<>();
        for (PostgresConfigStore.Row row : raw.tenants()) {
            try {
                tenants.put(row.id(), TenantValidator.validate(row.id(), mapper.readValue(row.specJson(), TenantSpec.class)));
            } catch (Exception e) {
                log.error("Skipping invalid tenant '{}' (revision {}): {}", row.id(), raw.revision(), e.getMessage());
                invalid.add("tenant:" + row.id());
            }
        }
        return new ConfigSnapshot(raw.revision(), routes.build(), tenants, invalid);
    }
}
