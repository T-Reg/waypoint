package dev.waypoint.visualizer.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the gateway stack lives and what the visualizer may mint. Profiles: application-compose.yml (local stack)
 * and application-app-platform.yml (DigitalOcean); every value can be overridden with VISUALIZER_* variables.
 * {@code haproxyStatsUrl} and {@code loadBalancerHealthUrl} may be blank; one of them describes the load balancer.
 */
@ConfigurationProperties("visualizer")
public record VisualizerProperties(
        Issuer issuer,
        Map<String, String> targets,
        String defaultTarget,
        Map<String, String> gatewayManagement,
        int managementProbes,
        String loadBalancerName,
        String loadBalancerHealthUrl,
        String haproxyStatsUrl,
        String upstreamAdminUrl,
        String jwksUrl,
        List<String> allowedTenants,
        Duration maxTtl,
        Duration requestTimeout,
        int maxRequestsPerRun,
        int maxConcurrency,
        Access access) {

    public VisualizerProperties {
        targets = Map.copyOf(targets);
        gatewayManagement = Map.copyOf(gatewayManagement);
        allowedTenants = List.copyOf(allowedTenants);
        if (!targets.containsKey(defaultTarget)) {
            throw new IllegalArgumentException("visualizer.default-target must be one of " + targets.keySet());
        }
        if (maxRequestsPerRun < 1 || maxConcurrency < 1 || managementProbes < 1) {
            throw new IllegalArgumentException(
                    "visualizer.max-requests-per-run, max-concurrency and management-probes must be positive");
        }
    }

    /** The customer-token issuer the visualizer stands in for: a private key file or inline JWK JSON. */
    public record Issuer(String keyFile, String key, String name, String audience) {
    }

    /** Basic auth gate. {@code required} makes startup fail without a password, for hosted deployments. */
    public record Access(boolean required, String username, String password) {

        public boolean enabled() {
            return password != null && !password.isBlank();
        }
    }
}
