package dev.waypoint.gateway.listener;

import dev.waypoint.gateway.admin.AdminGate;
import dev.waypoint.gateway.error.ProblemWriter;
import dev.waypoint.gateway.error.Rejection;
import dev.waypoint.gateway.pipeline.GatewayPipeline;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Dispatches by listener before anything else runs. Public traffic never reaches Spring MVC, the
 * admin listener serves only {@code /admin/*}, and management serves only JWKS, status and health.
 */
public class ListenerFilter extends OncePerRequestFilter {

    private static final Set<String> MANAGEMENT_PATHS = Set.of("/.well-known/jwks.json", "/internal/status", "/actuator/health");

    private final ListenerPorts ports;
    private final GatewayPipeline pipeline;
    private final AdminGate adminGate;
    private final ProblemWriter problems;

    public ListenerFilter(ListenerPorts ports, GatewayPipeline pipeline, AdminGate adminGate, ProblemWriter problems) {
        this.ports = ports;
        this.pipeline = pipeline;
        this.adminGate = adminGate;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (ports.resolve(request.getLocalPort())) {
            case PUBLIC -> pipeline.handle(request, response);
            case ADMIN -> adminGate.handle(request, response, chain);
            case MANAGEMENT -> {
                if (isManagementPath(request.getRequestURI())) {
                    chain.doFilter(request, response);
                } else {
                    notFound(response);
                }
            }
            case UNKNOWN -> notFound(response);
        }
    }

    static boolean isManagementPath(String uri) {
        return MANAGEMENT_PATHS.contains(uri) || uri.matches("/actuator/health/(liveness|readiness)");
    }

    private void notFound(HttpServletResponse response) throws IOException {
        problems.write(response, Rejection.of(404, "not_found", "not found"), null);
    }
}
