package dev.waypoint.gateway.tenant;

import java.util.List;

/** Tenant policy as written by admins or stored in the DB; nullable fields take defaults on validation. */
public record TenantSpec(
        List<String> allowedPathPrefixes,
        Integer requestsPerMinute,
        Boolean disabled,
        Boolean auditRawPath) {}
