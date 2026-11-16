package dev.waypoint.visualizer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.waypoint.visualizer.support.TestSupport;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessFilterTest {

    private static final VisualizerProperties.Access LOCKED = new VisualizerProperties.Access(true, "demo", "s3cret");

    private static MockHttpServletResponse call(AccessFilter filter, String uri, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void requiresCredentialsWhenAPasswordIsSet() throws Exception {
        AccessFilter filter = new AccessFilter(LOCKED);

        MockHttpServletResponse denied = call(filter, "/api/config", null);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(denied.getHeader("WWW-Authenticate")).startsWith("Basic realm=");
        assertThat(call(filter, "/api/config", basic("demo", "wrong")).getStatus()).isEqualTo(401);
        assertThat(call(filter, "/", basic("demo", "s3cret")).getStatus()).isEqualTo(200);
    }

    @Test
    void healthProbeStaysOpen() throws Exception {
        assertThat(call(new AccessFilter(LOCKED), "/actuator/health", null).getStatus()).isEqualTo(200);
    }

    @Test
    void passesThroughWhenNoPasswordIsSet() throws Exception {
        AccessFilter open = new AccessFilter(new VisualizerProperties.Access(false, "demo", ""));

        assertThat(call(open, "/api/config", null).getStatus()).isEqualTo(200);
        assertThat(new VisualizerProperties.Access(false, "demo", null).enabled()).isFalse();
    }

    @Test
    void refusesToStartWhenRequiredWithoutAPassword() {
        assertThatThrownBy(() -> new AccessFilter(new VisualizerProperties.Access(true, "demo", " ")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VISUALIZER_ACCESS_PASSWORD");
    }

    @Test
    void propertiesRejectInconsistentSettings() {
        VisualizerProperties base = TestSupport.properties("http://gw", "http://up", "http://jwks");

        assertThatThrownBy(() -> new VisualizerProperties(base.issuer(), Map.of("lb", "x"), "gateway-1", Map.of(), 1, "LB", "", "s", "u", "j",
                List.of("acme"), base.maxTtl(), base.requestTimeout(), 1, 1, base.access()))
                .hasMessageContaining("default-target");
        assertThatThrownBy(() -> new VisualizerProperties(base.issuer(), Map.of("lb", "x"), "lb", Map.of(), 1, "LB", "", "s", "u", "j",
                List.of("acme"), base.maxTtl(), base.requestTimeout(), 0, 1, base.access()))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new VisualizerProperties(base.issuer(), Map.of("lb", "x"), "lb", Map.of(), 1, "LB", "", "s", "u", "j",
                List.of("acme"), base.maxTtl(), base.requestTimeout(), 1, 0, base.access()))
                .hasMessageContaining("positive");
    }
}
