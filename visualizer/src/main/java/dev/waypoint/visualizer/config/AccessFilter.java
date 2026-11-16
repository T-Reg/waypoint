package dev.waypoint.visualizer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

/** HTTP Basic gate for everything except the liveness probe; passes through when no password is set. */
public class AccessFilter extends OncePerRequestFilter {

    private static final String HEALTH_PATH = "/actuator/health";

    private final byte[] expected;

    public AccessFilter(VisualizerProperties.Access access) {
        if (access.required() && !access.enabled()) {
            throw new IllegalStateException(
                    "visualizer.access.required is true but no password is set (VISUALIZER_ACCESS_PASSWORD)");
        }
        if (!access.enabled()) {
            this.expected = null;
            return;
        }
        String credentials = access.username() + ":" + access.password();
        this.expected = ("Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null || request.getRequestURI().equals(HEALTH_PATH) || authorized(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"waypoint-visualizer\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private boolean authorized(String header) {
        return header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8));
    }
}
