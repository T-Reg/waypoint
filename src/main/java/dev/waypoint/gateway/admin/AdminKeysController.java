package dev.waypoint.gateway.admin;

import dev.waypoint.gateway.auth.AdminIdentity;
import dev.waypoint.gateway.identity.KeyService;
import dev.waypoint.gateway.store.PostgresConfigStore;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/keys")
public class AdminKeysController {

    private final PostgresConfigStore store;
    private final KeyService keys;

    public AdminKeysController(PostgresConfigStore store, KeyService keys) {
        this.store = store;
        this.keys = keys;
    }

    /** Broadcasts a rotation; every instance publishes its new key now and signs with it from {@code activatesAt}. */
    @PostMapping("/rotate")
    public Map<String, Object> rotate(@RequestAttribute(AdminGate.IDENTITY_ATTRIBUTE) AdminIdentity admin) {
        AdminSupport.requireScope(admin, AdminSupport.SCOPE_KEYS);
        long generation = store.rotateKeys(admin.subject());
        keys.applyGeneration(generation);
        KeyService.Rotation rotation = keys.lastRotation();
        return Map.of("generation", generation, "kid", rotation.kid(), "activatesAt", rotation.activatesAt().toString());
    }
}
