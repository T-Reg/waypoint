package dev.waypoint.gateway.proxy;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Headers the gateway puts on every public-listener response: the request ID, and the serving instance's ID
 * when {@code gateway.proxy.expose-instance-header} is on. Set last, so an upstream can't override them.
 */
public final class ResponseStamp {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String INSTANCE_HEADER = "X-Gateway-Instance";

    private final String instanceId;

    public ResponseStamp(String instanceId, boolean exposeInstance) {
        this.instanceId = exposeInstance ? instanceId : null;
    }

    public static ResponseStamp requestIdOnly() {
        return new ResponseStamp(null, false);
    }

    public void apply(HttpServletResponse response, String requestId) {
        response.setHeader(REQUEST_ID_HEADER, requestId);
        if (instanceId != null) {
            response.setHeader(INSTANCE_HEADER, instanceId);
        }
    }
}
