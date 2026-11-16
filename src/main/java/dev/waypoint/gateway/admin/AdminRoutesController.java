package dev.waypoint.gateway.admin;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.waypoint.gateway.auth.AdminIdentity;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.RouteSpec;
import dev.waypoint.gateway.routing.RouteValidator;
import dev.waypoint.gateway.routing.ValidationException;
import dev.waypoint.gateway.store.ConfigNotFoundException;
import dev.waypoint.gateway.store.ConfigSync;
import dev.waypoint.gateway.store.PostgresConfigStore;
import dev.waypoint.gateway.store.PostgresConfigStore.Table;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/routes")
public class AdminRoutesController {

    private final PostgresConfigStore store;
    private final RouteValidator validator;
    private final ConfigSync sync;
    private final ObjectMapper mapper;

    public AdminRoutesController(PostgresConfigStore store, RouteValidator validator, ConfigSync sync, ObjectMapper mapper) {
        this.store = store;
        this.validator = validator;
        this.sync = sync;
        this.mapper = mapper;
    }

    @GetMapping
    public List<AdminSupport.Resource<RouteSpec>> list(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin) {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_ROUTES);
        return store.list(Table.ROUTES).stream().map(this::resource).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminSupport.Resource<RouteSpec>> get(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin,
                                                                @PathVariable String id) {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_ROUTES);
        PostgresConfigStore.Row row = store.get(Table.ROUTES, id).orElseThrow(() -> new ConfigNotFoundException("route not found"));
        return ResponseEntity.ok().eTag(AdminSupport.etag(row.version())).body(resource(row));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdminSupport.Resource<RouteSpec>> put(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin,
                                                                @PathVariable String id,
                                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                                @RequestBody RouteSpec spec) throws ValidationException {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_ROUTES);
        Long expected = AdminSupport.parseIfMatch(ifMatch);
        Route route = validator.validate(id, spec, RouteValidator.Mode.WRITE);
        PostgresConfigStore.WriteResult result = store.putRoute(route, expected, admin.subject());
        refreshQuietly();
        PostgresConfigStore.Row row = store.get(Table.ROUTES, id).orElseThrow();
        return AdminSupport.written(result, resource(row));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin,
                                       @PathVariable String id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_ROUTES);
        store.delete(Table.ROUTES, id, AdminSupport.parseIfMatch(ifMatch), admin.subject());
        refreshQuietly();
        return ResponseEntity.noContent().build();
    }

    private void refreshQuietly() {
        try {
            sync.refresh();
        } catch (RuntimeException ignored) {
            // The write committed; NOTIFY or the poll will converge this instance.
        }
    }

    private AdminSupport.Resource<RouteSpec> resource(PostgresConfigStore.Row row) {
        try {
            return new AdminSupport.Resource<>(row.id(), row.version(), row.updatedAt(), row.updatedBy(),
                    mapper.readValue(row.specJson(), RouteSpec.class));
        } catch (JacksonException e) {
            throw new AdminException(500, "admin.corrupt_entry", "stored route cannot be read");
        }
    }
}
