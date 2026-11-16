package dev.waypoint.visualizer.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.waypoint.visualizer.token.TokenFault;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestSpecTest {

    private static RequestSpec spec(String method, String path, String tenant, Map<String, String> headers, String body) {
        return new RequestSpec("lb", method, path, tenant, null, null, null, headers, body);
    }

    @Test
    void appliesDefaults() {
        RequestSpec spec = spec(null, "/weather", "acme", null, null);

        assertThat(spec.method()).isEqualTo("GET");
        assertThat(spec.subject()).isEqualTo("visualizer");
        assertThat(spec.fault()).isEqualTo(TokenFault.NONE);
        assertThat(spec.headers()).isEmpty();
    }

    @Test
    void normalizesMethodCaseAndKeepsEncodedPaths() {
        RequestSpec spec = spec("delete", "/weather/%2e%2e/maps;x", "acme", Map.of("X-Forwarded-For", "6.6.6.6"), "{}");

        assertThat(spec.method()).isEqualTo("DELETE");
        assertThat(spec.path()).isEqualTo("/weather/%2e%2e/maps;x");
        assertThat(spec.withTarget("gateway-1").target()).isEqualTo("gateway-1");
        assertThat(spec.withTarget("gateway-1").headers()).containsEntry("X-Forwarded-For", "6.6.6.6");
    }

    @ParameterizedTest
    @ValueSource(strings = {"weather", "/has space", "/new\nline", "/café"})
    void rejectsPathsTheUriCannotCarry(String path) {
        assertThatThrownBy(() -> spec("GET", path, "acme", null, null)).hasMessageContaining("path");
    }

    @Test
    void rejectsMissingPathTenantAndUnknownMethods() {
        assertThatThrownBy(() -> spec("GET", null, "acme", null, null)).hasMessageContaining("path");
        assertThatThrownBy(() -> spec("GET", "/x", " ", null, null)).hasMessageContaining("tenant");
        assertThatThrownBy(() -> spec("GET", "/x", null, null, null)).hasMessageContaining("tenant");
        assertThatThrownBy(() -> spec("TRACE", "/x", "acme", null, null)).hasMessageContaining("method");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "host", "Connection", "Content-Length", "Bad Header", ""})
    void rejectsReservedOrMalformedHeaderNames(String name) {
        assertThatThrownBy(() -> spec("GET", "/x", "acme", Map.of(name, "v"), null)).hasMessageContaining("not allowed");
    }

    @Test
    void rejectsHeaderValuesWithControlCharacters() {
        assertThatThrownBy(() -> spec("GET", "/x", "acme", Map.of("X-A", "a\r\nX-Injected: 1"), null))
                .hasMessageContaining("invalid value");
        assertThatThrownBy(() -> spec("GET", "/x", "acme", Map.of("X-A", "x".repeat(1025)), null))
                .hasMessageContaining("invalid value");
    }

    @Test
    void rejectsOversizedBodies() {
        assertThatThrownBy(() -> spec("POST", "/x", "acme", null, "x".repeat(16 * 1024 + 1))).hasMessageContaining("body");
    }
}
