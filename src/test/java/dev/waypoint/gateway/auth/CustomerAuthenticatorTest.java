package dev.waypoint.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import dev.waypoint.gateway.routing.RouteTable;
import dev.waypoint.gateway.store.ConfigSnapshot;
import dev.waypoint.gateway.support.MutableClock;
import dev.waypoint.gateway.support.TestTokens;
import dev.waypoint.gateway.tenant.Tenant;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustomerAuthenticatorTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final CustomerAuthenticator authenticator = new CustomerAuthenticator(new Ed25519JwtVerifier(
            new JWKSet(TestTokens.ISSUER_KEY.toPublicJWK()), "waypoint-issuer", "gateway", TokenTypes.CUSTOMER, Duration.ofSeconds(30), clock));
    private final ConfigSnapshot snapshot = new ConfigSnapshot(1, RouteTable.empty(), Map.of(
            "acme", new Tenant("acme", List.of("/weather"), 60, false, false),
            "initech", new Tenant("initech", List.of("/weather"), 60, true, false)), List.of());

    private static Enumeration<String> headers(String... values) {
        return Collections.enumeration(List.of(values));
    }

    private AuthResult auth(String... headerValues) {
        return authenticator.authenticate(headers(headerValues), snapshot);
    }

    private static String code(AuthResult result) {
        return result instanceof AuthResult.Failure f ? f.code() : "ok";
    }

    @Test
    void authenticatesAndResolvesTenantFromSignedClaim() {
        AuthResult result = auth("Bearer " + TestTokens.customer().clock(clock).scope("weather:read  maps:read").sign());
        assertThat(result).isInstanceOfSatisfying(AuthResult.Authenticated.class, ok -> {
            assertThat(ok.identity().tenantId()).isEqualTo("acme");
            assertThat(ok.identity().subject()).isEqualTo("web-app");
            assertThat(ok.identity().scopes()).containsExactlyInAnyOrder("weather:read", "maps:read");
            assertThat(ok.tenant().id()).isEqualTo("acme");
        });
    }

    @Test
    void schemeIsCaseInsensitive() {
        assertThat(code(auth("bearer " + TestTokens.customer().clock(clock).sign()))).isEqualTo("ok");
    }

    @Test
    void missingToken() {
        assertThat(code(authenticator.authenticate(null, snapshot))).isEqualTo(CustomerAuthenticator.MISSING);
        assertThat(code(auth())).isEqualTo(CustomerAuthenticator.MISSING);
    }

    @Test
    void malformedAuthorizationHeader() {
        assertThat(code(auth("Basic dXNlcjpwYXNz"))).isEqualTo(CustomerAuthenticator.INVALID);
        assertThat(code(auth("Bearer"))).isEqualTo(CustomerAuthenticator.INVALID);
        assertThat(code(auth("Bearer a b"))).isEqualTo(CustomerAuthenticator.INVALID);
        assertThat(code(auth("Bearer x", "Bearer y"))).isEqualTo(CustomerAuthenticator.INVALID);
    }

    @Test
    void invalidAndExpiredTokens() {
        assertThat(code(auth("Bearer garbage.token.here"))).isEqualTo(CustomerAuthenticator.INVALID);
        String token = TestTokens.customer().clock(clock).ttl(Duration.ofSeconds(10)).sign();
        clock.advance(Duration.ofMinutes(5));
        assertThat(code(auth("Bearer " + token))).isEqualTo(CustomerAuthenticator.EXPIRED);
    }

    @Test
    void missingOrNonStringTenantClaim() {
        assertThat(code(auth("Bearer " + TestTokens.customer().clock(clock).without("tid").sign()))).isEqualTo(CustomerAuthenticator.INVALID);
        assertThat(code(auth("Bearer " + TestTokens.customer().clock(clock).tenant(42).sign()))).isEqualTo(CustomerAuthenticator.INVALID);
        assertThat(code(auth("Bearer " + TestTokens.customer().clock(clock).tenant(" ").sign()))).isEqualTo(CustomerAuthenticator.INVALID);
    }

    @Test
    void nonStringScopeClaim() {
        assertThat(code(auth("Bearer " + TestTokens.customer().clock(clock).scope(List.of("weather:read")).sign())))
                .isEqualTo(CustomerAuthenticator.INVALID);
    }

    @Test
    void unknownOrDisabledTenant() {
        assertThat(code(auth("Bearer " + TestTokens.customer().clock(clock).tenant("globex").sign()))).isEqualTo(CustomerAuthenticator.UNKNOWN_TENANT);
        assertThat(code(auth("Bearer " + TestTokens.customer().clock(clock).tenant("initech").sign()))).isEqualTo(CustomerAuthenticator.UNKNOWN_TENANT);
    }

    @Test
    void adminTokenIsRejectedAsCustomerToken() {
        assertThat(code(auth("Bearer " + TestTokens.admin().clock(clock).scope("admin:routes").sign()))).isEqualTo(CustomerAuthenticator.INVALID);
    }

    @Test
    void parsesScopes() {
        assertThat(CustomerAuthenticator.parseScopes(null)).isEmpty();
        assertThat(CustomerAuthenticator.parseScopes("  ")).isEmpty();
        assertThat(CustomerAuthenticator.parseScopes(" a  b ")).isEqualTo(Set.of("a", "b"));
    }
}
