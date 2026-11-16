package dev.waypoint.gateway.auth;

import dev.waypoint.gateway.tenant.Tenant;

public sealed interface AuthResult {

    record Authenticated(TenantIdentity identity, Tenant tenant) implements AuthResult {}

    record Failure(String code, String detail) implements AuthResult {}
}
