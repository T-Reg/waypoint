package dev.waypoint.gateway.pipeline;

import dev.waypoint.gateway.audit.AuditEntry.Decision;
import dev.waypoint.gateway.auth.TenantIdentity;
import dev.waypoint.gateway.proxy.ClientIpResolver.ClientAddress;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.store.ConfigSnapshot;
import dev.waypoint.gateway.tenant.Tenant;
import jakarta.servlet.http.HttpServletRequest;

/** Per-request state filled in by each stage; pinned to the snapshot current when the request arrived. */
public final class RequestContext {

    private final String requestId;
    private final HttpServletRequest request;
    private final ConfigSnapshot snapshot;
    private final ClientAddress client;
    private final long startNanos;
    private String normalizedPath;
    private TenantIdentity identity;
    private Tenant tenant;
    private Route route;
    private String upstreamOrigin;
    private Decision decision = Decision.DENY;
    private String denyCode;
    private String errorCode;
    private Integer upstreamStatus;

    public RequestContext(String requestId, HttpServletRequest request, ConfigSnapshot snapshot, ClientAddress client, long startNanos) {
        this.requestId = requestId;
        this.request = request;
        this.snapshot = snapshot;
        this.client = client;
        this.startNanos = startNanos;
    }

    public String requestId() { return requestId; }
    public HttpServletRequest request() { return request; }
    public String method() { return request.getMethod(); }
    public ConfigSnapshot snapshot() { return snapshot; }
    public ClientAddress client() { return client; }
    public long startNanos() { return startNanos; }
    public String normalizedPath() { return normalizedPath; }
    public TenantIdentity identity() { return identity; }
    public Tenant tenant() { return tenant; }
    public Route route() { return route; }
    public String upstreamOrigin() { return upstreamOrigin; }
    public Decision decision() { return decision; }
    public String denyCode() { return denyCode; }
    public String errorCode() { return errorCode; }
    public Integer upstreamStatus() { return upstreamStatus; }

    void normalizedPath(String path) { this.normalizedPath = path; }
    void authenticated(TenantIdentity identity, Tenant tenant) { this.identity = identity; this.tenant = tenant; }
    void route(Route route) { this.route = route; }
    void deny(String code) { this.decision = Decision.DENY; this.denyCode = code; }
    void allow() { this.decision = Decision.ALLOW; }
    public void upstreamOrigin(String origin) { this.upstreamOrigin = origin; }
    public void error(String code) { this.errorCode = code; }
    public void upstreamStatus(int status) { this.upstreamStatus = status; }
}
