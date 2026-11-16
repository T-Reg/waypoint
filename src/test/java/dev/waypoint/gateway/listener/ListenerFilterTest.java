package dev.waypoint.gateway.listener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ListenerFilterTest {

    @ParameterizedTest
    @CsvSource({
            "/.well-known/jwks.json, true",
            "/internal/status, true",
            "/actuator/health, true",
            "/actuator/health/readiness, true",
            "/actuator/health/liveness, true",
            "/actuator/health/db, false",
            "/actuator, false",
            "/actuator/env, false",
            "/admin/routes, false",
            "/.well-known/jwks.json;x, false"
    })
    void managementListenerServesOnlyItsPaths(String path, boolean allowed) {
        assertThat(ListenerFilter.isManagementPath(path)).isEqualTo(allowed);
    }
}
