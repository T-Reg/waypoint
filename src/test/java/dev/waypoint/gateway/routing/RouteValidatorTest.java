package dev.waypoint.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.waypoint.gateway.support.Fixtures;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteValidatorTest {

    private final RouteValidator validator = new RouteValidator(new UpstreamAddressPolicy(Fixtures.noListeners()));

    private static RouteSpec spec(String prefix, String url, Map<String, List<String>> scopes) {
        return new RouteSpec(prefix, List.of(new Upstream(url, null)), null, scopes, null, null, null, null, null);
    }

    @Test
    void appliesDefaults() throws ValidationException {
        Route route = validator.validate("weather", spec("/weather", "https://93.184.216.34", Map.of("GET", List.of("weather:read"))),
                RouteValidator.Mode.LOAD);
        assertThat(route.audience()).isEqualTo("weather");
        assertThat(route.forwardIdentity()).isTrue();
        assertThat(route.stripPrefix()).isFalse();
        assertThat(route.timeoutMs()).isEqualTo(10_000);
        assertThat(route.allowPrivateNetwork()).isFalse();
        assertThat(route.forwardScopes()).isNull();
        assertThat(route.upstreams().get(0).weight()).isEqualTo(100);
    }

    @Test
    void keepsExplicitValues() throws ValidationException {
        RouteSpec spec = new RouteSpec("/w", List.of(new Upstream("http://127.0.0.1:7000", 10)), true, Map.of("*", List.of()),
                List.of("weather:*"), false, "weather-svc", 500, true);
        Route route = validator.validate("w", spec, RouteValidator.Mode.WRITE);
        assertThat(route.stripPrefix()).isTrue();
        assertThat(route.forwardIdentity()).isFalse();
        assertThat(route.audience()).isEqualTo("weather-svc");
        assertThat(route.timeoutMs()).isEqualTo(500);
        assertThat(route.forwardScopes()).containsExactly("weather:*");
    }

    @Test
    void collectsAllErrors() {
        Map<String, List<String>> scopes = new HashMap<>();
        scopes.put("get", List.of("ok"));
        scopes.put("CONNECT", List.of());
        scopes.put("TRACE", List.of());
        scopes.put("PUT", Arrays.asList("bad scope", null));
        RouteSpec spec = new RouteSpec("weather/", List.of(new Upstream("http://169.254.169.254", 101)), null, scopes,
                Arrays.asList("bad pattern", null), null, " ", 0, null);
        assertThatThrownBy(() -> validator.validate("Bad Id", spec, RouteValidator.Mode.LOAD))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors()).hasSizeGreaterThanOrEqualTo(10));
    }

    @Test
    void requiresBodyUpstreamsAndScopes() {
        assertThatThrownBy(() -> validator.validate("x", null, RouteValidator.Mode.LOAD)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> validator.validate("x", new RouteSpec("/x", null, null, null, null, null, null, null, null), RouteValidator.Mode.LOAD))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.errors()).hasSize(2));
        assertThatThrownBy(() -> validator.validate("x", new RouteSpec("/x", Arrays.asList((Upstream) null), null, Map.of("*", List.of()),
                null, null, null, null, null), RouteValidator.Mode.LOAD)).isInstanceOf(ValidationException.class);
        Map<String, List<String>> nullScopes = new HashMap<>();
        nullScopes.put("GET", null);
        assertThat(catchErrors(new RouteSpec("/x", List.of(new Upstream("https://93.184.216.34", 1)), null, nullScopes, null, null, null, null, null)))
                .isEmpty();
    }

    @Test
    void writeModeResolvesHostnames() {
        RouteSpec spec = spec("/x", "http://localhost:1234", Map.of("*", List.of()));
        assertThat(catchErrors(spec, RouteValidator.Mode.LOAD)).isEmpty();
        assertThat(catchErrors(spec, RouteValidator.Mode.WRITE)).anyMatch(e -> e.contains("private or loopback"));
    }

    @Test
    void rejectsOverlongAudienceAndTimeouts() {
        RouteSpec spec = new RouteSpec("/x", List.of(new Upstream("https://93.184.216.34", 1)), null, Map.of("*", List.of()),
                null, null, "a".repeat(257), 300_001, null);
        assertThat(catchErrors(spec)).hasSize(2);
    }

    private List<String> catchErrors(RouteSpec spec) {
        return catchErrors(spec, RouteValidator.Mode.LOAD);
    }

    private List<String> catchErrors(RouteSpec spec, RouteValidator.Mode mode) {
        try {
            validator.validate("x", spec, mode);
            return List.of();
        } catch (ValidationException e) {
            return e.errors();
        }
    }
}
