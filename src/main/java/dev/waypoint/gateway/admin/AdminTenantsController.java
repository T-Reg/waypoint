package dev.waypoint.gateway.admin;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.waypoint.gateway.auth.AdminIdentity;
import dev.waypoint.gateway.tenant.Tenant;
import dev.waypoint.gateway.tenant.TenantSpec;
import dev.waypoint.gateway.tenant.TenantValidator;
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
@RequestMapping("/admin/tenants")
public class AdminTenantsController {

    private final PostgresConfigStore store;
    private final ConfigSync sync;
    private final ObjectMapper mapper;

    public AdminTenantsController(PostgresConfigStore store, ConfigSync sync, ObjectMapper mapper) {
        this.store = store;
        this.sync = sync;
        this.mapper = mapper;
    }

    @GetMapping
    public List<AdminSupport.Resource<TenantSpec>> list(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin) {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_TENANTS);
        return store.list(Table.TENANTS).stream().map(this::resource).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminSupport.Resource<TenantSpec>> get(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin,
                                                                @PathVariable String id) {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_TENANTS);
        PostgresConfigStore.Row row = store.get(Table.TENANTS, id).orElseThrow(() -> new ConfigNotFoundException("tenant not found"));
        return ResponseEntity.ok().eTag(AdminSupport.etag(row.version())).body(resource(row));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdminSupport.Resource<TenantSpec>> put(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin,
                                                                @PathVariable String id,
                                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                                @RequestBody TenantSpec spec) throws ValidationException {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_TENANTS);
        Long expected = AdminSupport.parseIfMatch(ifMatch);
        Tenant tenant = TenantValidator.validate(id, spec);
        PostgresConfigStore.WriteResult result = store.putTenant(tenant, expected, admin.subject());
        refreshQuietly();
        PostgresConfigStore.Row row = store.get(Table.TENANTS, id).orElseThrow();
        return AdminSupport.written(result, resource(row));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin,
                                       @PathVariable String id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_TENANTS);
        store.delete(Table.TENANTS, id, AdminSupport.parseIfMatch(ifMatch), admin.subject());
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

    private AdminSupport.Resource<TenantSpec> resource(PostgresConfigStore.Row row) {
        try {
            return new AdminSupport.Resource<>(row.id(), row.version(), row.updatedAt(), row.updatedBy(),
                    mapper.readValue(row.specJson(), TenantSpec.class));
        } catch (JacksonException e) {
            throw new AdminException(500, "admin.corrupt_entry", "stored tenant cannot be read");
        }
    }
}
