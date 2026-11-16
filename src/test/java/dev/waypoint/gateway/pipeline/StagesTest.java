package dev.waypoint.gateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import dev.waypoint.gateway.auth.CustomerAuthenticator;
import dev.waypoint.gateway.auth.Ed25519JwtVerifier;
import dev.waypoint.gateway.auth.TokenTypes;
import dev.waypoint.gateway.error.Rejection;
import dev.waypoint.gateway.proxy.ClientIpResolver;
import dev.waypoint.gateway.ratelimit.LocalRateLimiter;
import dev.waypoint.gateway.routing.RouteTable;
import dev.waypoint.gateway.store.ConfigSnapshot;
import dev.waypoint.gateway.support.Fixtures;
import dev.waypoint.gateway.support.MutableClock;
import dev.waypoint.gateway.support.TestTokens;
import dev.waypoint.gateway.tenant.Tenant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StagesTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final CustomerAuthenticator authenticator = new CustomerAuthenticator(new Ed25519JwtVerifier(
            new JWKSet(TestTokens.ISSUER_KEY.toPublicJWK()), "waypoint-issuer", "gateway", TokenTypes.CUSTOMER, Duration.ofSeconds(30), clock));
    private final List<Stage> stages = Stages.ordered(100, authenticator, new LocalRateLimiter(clock));
    private final ConfigSnapshot snapshot = new ConfigSnapshot(1, RouteTable.builder()
            .add(Fixtures.route("weather", "/weather", Map.of("GET", List.of("weather:read"), "POST", List.of("weather:write"))))
            .add(Fixtures.route("maps", "/maps", Map.of("*", List.of("maps:read"))))
            .add(Fixtures.route("open", "/open", Map.of("*", List.of())))
            .addUnavailable("broken", "/broken")
            .build(), Map.of(
            "acme", new Tenant("acme", List.of("/weather", "/maps", "/broken", "/open", "/nowhere"), 2, false, false),
            "globex", new Tenant("globex", List.of("/weather"), 60, false, false)), List.of());

    private Optional<Rejection> run(MockHttpServletRequest request) {
        RequestContext ctx = new RequestContext("rid", request, snapshot, new ClientIpResolver.ClientAddress("1.1.1.1", List.of("1.1.1.1"), false), 0);
        for (Stage stage : stages) {
            Optional<Rejection> r = stage.apply(ctx);
            if (r.isPresent()) {
                return r;
            }
        }
        return Optional.empty();
    }

    private MockHttpServletRequest request(String method, String uri, String tenant, String scope) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (tenant != null) {
            request.addHeader("Authorization", "Bearer " + TestTokens.customer().clock(clock).tenant(tenant).scope(scope).sign());
        }
        return request;
    }

    private static String code(Optional<Rejection> r) {
        return r.map(Rejection::code).orElse("continue");
    }

    @Test
    void allowsWhenEveryCheckPasses() {
        assertThat(code(run(request("GET", "/weather/today", "acme", "weather:read")))).isEqualTo("continue");
        assertThat(code(run(request("DELETE", "/open/x", "acme", null)))).isEqualTo("continue");
    }

    @Test
    void bodyTooLarge() {
        MockHttpServletRequest request = request("POST", "/weather", "acme", "weather:write");
        request.setContent(new byte[101]);
        Optional<Rejection> r = run(request);
        assertThat(r.orElseThrow().status()).isEqualTo(413);
        assertThat(code(r)).isEqualTo(Stages.TOO_LARGE);
    }

    @Test
    void badPathBeforeAuth() {
        Optional<Rejection> r = run(request("GET", "/weather/..;/admin", null, null));
        assertThat(r.orElseThrow().status()).isEqualTo(400);
        assertThat(code(r)).isEqualTo(Stages.BAD_PATH);
    }

    @Test
    void missingTokenHasPlainBearerChallenge() {
        Rejection r = run(request("GET", "/weather", null, null)).orElseThrow();
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.headers()).containsEntry("WWW-Authenticate", "Bearer");
    }

    @Test
    void invalidTokenHasInvalidTokenChallenge() {
        MockHttpServletRequest request = request("GET", "/weather", null, null);
        request.addHeader("Authorization", "Bearer nope");
        Rejection r = run(request).orElseThrow();
        assertThat(r.code()).isEqualTo(CustomerAuthenticator.INVALID);
        assertThat(r.headers()).containsEntry("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    }

    @Test
    void pathOutsideAllowlistIs403EvenIfRouteExists() {
        assertThat(code(run(request("GET", "/maps", "globex", "maps:read")))).isEqualTo(Stages.PATH_DENIED);
        assertThat(code(run(request("GET", "/nonexistent", "globex", null)))).isEqualTo(Stages.PATH_DENIED);
    }

    @Test
    void unknownRouteInsideAllowlistIs404() {
        assertThat(code(run(request("GET", "/nowhere", "acme", null)))).isEqualTo(Stages.ROUTE_NOT_FOUND);
    }

    @Test
    void invalidRouteEntryIs503() {
        Rejection r = run(request("GET", "/broken/x", "acme", null)).orElseThrow();
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo(Stages.ROUTE_UNAVAILABLE);
    }

    @Test
    void unconfiguredMethodIs405WithAllow() {
        Rejection r = run(request("DELETE", "/weather", "acme", "weather:read weather:write")).orElseThrow();
        assertThat(r.status()).isEqualTo(405);
        assertThat(r.code()).isEqualTo(Stages.METHOD_DENIED);
        assertThat(r.headers()).containsEntry("Allow", "GET, POST");
    }

    @Test
    void missingScopeForMethodIncludingWildcardFallback() {
        assertThat(code(run(request("POST", "/weather", "acme", "weather:read")))).isEqualTo(Stages.SCOPE_MISSING);
        assertThat(code(run(request("PATCH", "/maps", "acme", "weather:read")))).isEqualTo(Stages.SCOPE_MISSING);
        assertThat(code(run(request("PATCH", "/maps", "acme", "maps:read")))).isEqualTo("continue");
    }

    @Test
    void quotaExceededHasRetryAfter() {
        assertThat(code(run(request("GET", "/open", "acme", null)))).isEqualTo("continue");
        assertThat(code(run(request("GET", "/open", "acme", null)))).isEqualTo("continue");
        Rejection r = run(request("GET", "/open", "acme", null)).orElseThrow();
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.headers()).containsEntry("Retry-After", "60");
    }

    @Test
    void deniedRequestsDoNotConsumeQuota() {
        for (int i = 0; i < 5; i++) {
            run(request("POST", "/weather", "acme", "weather:read"));
        }
        assertThat(code(run(request("GET", "/open", "acme", null)))).isEqualTo("continue");
    }
}
