package dev.waypoint.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScopeFilterTest {

    @Test
    void namespacePatternsAndExactMatches() {
        Set<String> granted = Set.of("weather:read", "weather:write", "billing:write", "maps:read", "weatherx:read");
        assertThat(ScopeFilter.filter(granted, List.of("weather:*"))).isEqualTo("weather:read weather:write");
        assertThat(ScopeFilter.filter(granted, List.of("maps:read", "billing:read"))).isEqualTo("maps:read");
        assertThat(ScopeFilter.filter(granted, List.of())).isEmpty();
    }

    @Test
    void unrelatedScopesNeverForwarded() {
        assertThat(ScopeFilter.filter(Set.of("billing:write", "admin:routes"), List.of("weather:*"))).isEmpty();
    }
}
