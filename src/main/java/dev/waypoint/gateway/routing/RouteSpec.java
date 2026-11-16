package dev.waypoint.gateway.routing;

import java.util.List;
import java.util.Map;

/** Route as written by admins or stored in the DB; nullable fields take defaults on validation. */
public record RouteSpec(
        String pathPrefix,
        List<Upstream> upstreams,
        Boolean stripPrefix,
        Map<String, List<String>> requiredScopes,
        List<String> forwardScopes,
        Boolean forwardIdentity,
        String audience,
        Integer timeoutMs,
        Boolean allowPrivateNetwork) {}
