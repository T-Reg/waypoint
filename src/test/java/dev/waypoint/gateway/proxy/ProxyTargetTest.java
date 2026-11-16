package dev.waypoint.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.Upstream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyTargetTest {

    private static Route route(boolean strip) {
        return new Route("w", "/weather", List.of(new Upstream("http://up:8080/base/", 100)), strip, Map.of("*", List.of()),
                null, true, "w", 1000, true);
    }

    @Test
    void forwardsCanonicalReEncodedPathAndRawQuery() {
        URI target = ProxyHandler.targetUri(URI.create("http://up:8080/base/"), route(false), "/weather/san josé", "q=a%20b&x=1");
        assertThat(target.toString()).isEqualTo("http://up:8080/base/weather/san%20jos%C3%A9?q=a%20b&x=1");
    }

    @Test
    void stripsPrefixWhenConfigured() {
        assertThat(ProxyHandler.targetUri(URI.create("http://up:8080"), route(true), "/weather/today", null).toString())
                .isEqualTo("http://up:8080/today");
        assertThat(ProxyHandler.targetUri(URI.create("http://up:8080"), route(true), "/weather", null).toString())
                .isEqualTo("http://up:8080/");
    }
}
