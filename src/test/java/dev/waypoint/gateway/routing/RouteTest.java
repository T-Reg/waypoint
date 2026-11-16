package dev.waypoint.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.support.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteTest {

    @Test
    void requiredScopesFallBackToWildcard() {
        Route route = Fixtures.route("w", "/weather", Map.of("GET", List.of("weather:read"), "*", List.of("weather:admin")));
        assertThat(route.requiredScopesFor("GET")).contains(List.of("weather:read"));
        assertThat(route.requiredScopesFor("DELETE")).contains(List.of("weather:admin"));
    }

    @Test
    void unconfiguredMethodHasNoEntry() {
        Route route = Fixtures.route("w", "/weather", Map.of("GET", List.of("weather:read"), "POST", List.of()));
        assertThat(route.requiredScopesFor("DELETE")).isEmpty();
        assertThat(route.requiredScopesFor("POST")).contains(List.of());
        assertThat(route.configuredMethods()).containsExactly("GET", "POST");
    }

    @Test
    void defaultForwardScopesAreRequiredNamespaces() {
        Route route = Fixtures.route("w", "/weather", Map.of("GET", List.of("weather:read"), "POST", List.of("weather:write", "audit:log", "plain")));
        assertThat(route.effectiveForwardScopes()).containsExactly("audit:*", "weather:*");
    }

    @Test
    void noRequiredScopesMeansNothingForwardedByDefault() {
        assertThat(Fixtures.route("w", "/weather", Map.of("*", List.of())).effectiveForwardScopes()).isEmpty();
    }

    @Test
    void explicitForwardScopesWin() {
        Route base = Fixtures.route("w", "/weather", Map.of("GET", List.of("weather:read")));
        Route explicit = new Route(base.id(), base.pathPrefix(), base.upstreams(), false, base.requiredScopes(), List.of("maps:read"),
                true, "w", 1000, true);
        assertThat(explicit.effectiveForwardScopes()).containsExactly("maps:read");
        assertThat(explicit.toSpec().forwardScopes()).containsExactly("maps:read");
    }
}
