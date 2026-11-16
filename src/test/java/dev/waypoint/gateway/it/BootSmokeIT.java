package dev.waypoint.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.TestGateway;
import dev.waypoint.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;

class BootSmokeIT {

    @Test
    void bootsAndRejectsUnauthenticated() {
        try (TestGateway gw = TestGateway.builder(Containers.newDatabase()).name("smoke").startReady()) {
            assertThat(gw.get("/weather", null).statusCode()).isEqualTo(401);
            assertThat(gw.status().path("revision").asLong()).isEqualTo(0);
            assertThat(gw.putTenant("acme", "{\"allowedPathPrefixes\":[\"/weather\"],\"requestsPerMinute\":60}").statusCode()).isEqualTo(201);
            assertThat(gw.get("/weather", TestTokens.customer("acme", "web", "weather:read")).statusCode()).isEqualTo(404);
        }
    }
}
