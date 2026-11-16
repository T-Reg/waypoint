package dev.waypoint.gateway.tenant;

import dev.waypoint.gateway.path.PathPrefix;
import java.util.List;

public record Tenant(
        String id,
        List<String> allowedPathPrefixes,
        int requestsPerMinute,
        boolean disabled,
        boolean auditRawPath) {

    public Tenant {
        allowedPathPrefixes = List.copyOf(allowedPathPrefixes);
    }

    public boolean allows(String canonicalPath) {
        return allowedPathPrefixes.stream().anyMatch(prefix -> PathPrefix.matches(prefix, canonicalPath));
    }

    public TenantSpec toSpec() {
        return new TenantSpec(allowedPathPrefixes, requestsPerMinute, disabled, auditRawPath);
    }
}
