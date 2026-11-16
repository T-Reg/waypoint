package dev.waypoint.gateway.store;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.RouteSpec;
import dev.waypoint.gateway.routing.RouteValidator;
import dev.waypoint.gateway.routing.ValidationException;
import dev.waypoint.gateway.tenant.Tenant;
import dev.waypoint.gateway.tenant.TenantSpec;
import dev.waypoint.gateway.tenant.TenantValidator;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;

/** Applies the YAML seed only when the store has never been written ({@code config_revision = 0}). */
public class Seeder {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    record SeedFile(Map<String, RouteSpec> routes, Map<String, TenantSpec> tenants) {}

    private final PostgresConfigStore store;
    private final RouteValidator routeValidator;
    private final ResourceLoader resources;
    private final boolean enabled;
    private final String location;

    public Seeder(PostgresConfigStore store, RouteValidator routeValidator, ResourceLoader resources, boolean enabled, String location) {
        this.store = store;
        this.routeValidator = routeValidator;
        this.resources = resources;
        this.enabled = enabled;
        this.location = location;
    }

    public boolean seedIfEmpty() throws IOException, ValidationException {
        if (!enabled || location == null || location.isBlank()) {
            return false;
        }
        SeedFile seed;
        try (InputStream in = resources.getResource(location).getInputStream()) {
            seed = YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build().readValue(in, SeedFile.class);
        }
        List<Route> routes = new ArrayList<>();
        List<Tenant> tenants = new ArrayList<>();
        for (Map.Entry<String, RouteSpec> entry : (seed.routes() == null ? Map.<String, RouteSpec>of() : seed.routes()).entrySet()) {
            routes.add(routeValidator.validate(entry.getKey(), entry.getValue(), RouteValidator.Mode.LOAD));
        }
        for (Map.Entry<String, TenantSpec> entry : (seed.tenants() == null ? Map.<String, TenantSpec>of() : seed.tenants()).entrySet()) {
            tenants.add(TenantValidator.validate(entry.getKey(), entry.getValue()));
        }
        boolean applied = store.seedIfEmpty(routes, tenants, "seed");
        log.info(applied ? "Seeded {} routes and {} tenants from {}" : "Store already initialized; seed {} routes/{} tenants from {} skipped",
                routes.size(), tenants.size(), location);
        return applied;
    }
}
