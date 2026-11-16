package dev.waypoint.gateway.admin;

import dev.waypoint.gateway.audit.AuditRecorder;
import dev.waypoint.gateway.auth.AdminAuthenticator;
import dev.waypoint.gateway.auth.AdminIdentity;
import dev.waypoint.gateway.error.ProblemWriter;
import dev.waypoint.gateway.error.Rejection;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/** Admin listener front door: admin JWT required for everything, and every request is audited. */
public class AdminGate {

    public static final String IDENTITY_ATTRIBUTE = "waypoint.adminIdentity";
    public static final String DENY_CODE_ATTRIBUTE = "waypoint.adminDenyCode";
    public static final String REQUEST_ID_ATTRIBUTE = "waypoint.requestId";

    private final AdminAuthenticator authenticator;
    private final ProblemWriter problems;
    private final AuditRecorder audit;

    public AdminGate(AdminAuthenticator authenticator, ProblemWriter problems, AuditRecorder audit) {
        this.authenticator = authenticator;
        this.problems = problems;
        this.audit = audit;
    }

    public void handle(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        String path = request.getRequestURI();
        String actor = null;
        String denyCode = null;
        response.setHeader("X-Request-Id", requestId);
        try {
            if (!path.startsWith("/admin/") || path.contains(";") || path.contains("%")) {
                denyCode = "admin.not_found";
                problems.write(response, Rejection.of(404, denyCode, "not found"), requestId);
                return;
            }
            switch (authenticator.authenticate(request.getHeaders("Authorization"))) {
                case AdminAuthenticator.Result.Failure failure -> {
                    denyCode = "admin.unauthorized";
                    problems.write(response, Rejection.of(401, denyCode, failure.detail())
                            .withHeader("WWW-Authenticate", "Bearer realm=\"gateway-admin\""), requestId);
                }
                case AdminAuthenticator.Result.Authenticated ok -> {
                    AdminIdentity identity = ok.identity();
                    actor = identity.subject();
                    request.setAttribute(IDENTITY_ATTRIBUTE, identity);
                    request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
                    chain.doFilter(request, response);
                    denyCode = (String) request.getAttribute(DENY_CODE_ATTRIBUTE);
                }
            }
        } finally {
            audit.recordAdmin(new AuditRecorder.AdminRequest(requestId, request.getMethod(), path, actor,
                    response.getStatus(), denyCode, start, request.getRemoteAddr()));
        }
    }
}
