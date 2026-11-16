package dev.waypoint.gateway.auth;

import java.util.Set;

/** The verified caller: tenant and caller IDs and scopes come only from signed claims. */
public record TenantIdentity(String tenantId, String subject, Set<String> scopes) {

    public TenantIdentity {
        scopes = Set.copyOf(scopes);
    }
}
