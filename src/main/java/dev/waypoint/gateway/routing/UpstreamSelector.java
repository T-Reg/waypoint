package dev.waypoint.gateway.routing;

import dev.waypoint.gateway.auth.TenantIdentity;
import java.net.URI;

/** Seam for canary routing (design.md §10): weighted or tenant-sticky choice over {@code route.upstreams}. */
public interface UpstreamSelector {

    URI select(Route route, TenantIdentity identity);

    static UpstreamSelector primary() {
        return (route, identity) -> route.primaryUpstream();
    }
}
