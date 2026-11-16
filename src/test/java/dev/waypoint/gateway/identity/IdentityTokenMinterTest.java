package dev.waypoint.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.gateway.auth.Ed25519JwtVerifier;
import dev.waypoint.gateway.auth.TenantIdentity;
import dev.waypoint.gateway.auth.TokenException;
import dev.waypoint.gateway.auth.TokenTypes;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.Upstream;
import dev.waypoint.gateway.support.MutableClock;
import dev.waypoint.gateway.support.TestTokens;
import dev.waypoint.gateway.support.UpstreamVerifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityTokenMinterTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final GatewayKeyStore keys = new GatewayKeyStore(clock, Duration.ofSeconds(90));
    private final IdentityTokenMinter minter = new IdentityTokenMinter(keys, "waypoint-gateway", Duration.ofSeconds(60), clock);
    private final Route weather = new Route("weather", "/weather", List.of(new Upstream("http://127.0.0.1:1", 100)), false,
            Map.of("GET", List.of("weather:read")), null, true, "weather-svc", 1000, true);
    private final TenantIdentity identity = new TenantIdentity("acme", "web-app", Set.of("weather:read", "billing:write", "maps:read"));

    IdentityTokenMinterTest() {
        keys.activate(GatewayKeyStore.generate());
    }

    private Ed25519JwtVerifier verifierFor(String audience) {
        return new Ed25519JwtVerifier(keys.publicJwks(), "waypoint-gateway", audience, TokenTypes.IDENTITY, Duration.ofSeconds(30), clock);
    }

    @Test
    void mintsAudienceBoundShortLivedTokenWithFilteredScopes() throws Exception {
        String token = minter.mint(identity, weather, "req-1");
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(keys.currentSigningKey().getKeyID());
        assertThat(jwt.getHeader().getType()).isEqualTo(TokenTypes.IDENTITY);
        JWTClaimsSet claims = verifierFor("weather-svc").verify(token);
        assertThat(claims.getAudience()).containsExactly("weather-svc");
        assertThat(claims.getClaim("tid")).isEqualTo("acme");
        assertThat(claims.getSubject()).isEqualTo("web-app");
        assertThat(claims.getClaim("scope")).isEqualTo("weather:read");
        assertThat(claims.getClaim("rid")).isEqualTo("req-1");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant())).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void verifiesViaPublishedJwksLikeAnUpstream() throws Exception {
        // Nimbus' processor checks exp against the system clock, so mint against it here.
        IdentityTokenMinter realTime = new IdentityTokenMinter(keys, "waypoint-gateway", Duration.ofSeconds(60), java.time.Clock.systemUTC());
        UpstreamVerifier upstream = UpstreamVerifier.fixed(keys.publicJwks().toString(), "weather-svc");
        assertThat(upstream.verify(realTime.mint(identity, weather, "req-2")).getClaim("tid")).isEqualTo("acme");
        assertThat(UpstreamVerifier.fixed(keys.publicJwks().toString(), "billing-svc").accepts(realTime.mint(identity, weather, "req-3"))).isFalse();
    }

    @Test
    void omitsScopeWhenNothingMatches() throws Exception {
        Route noScopes = new Route("w", "/w", weather.upstreams(), false, Map.of("*", List.of()), null, true, "w", 1000, true);
        JWTClaimsSet claims = verifierFor("w").verify(minter.mint(identity, noScopes, "r"));
        assertThat(claims.getClaim("scope")).isNull();
    }

    @Test
    void rejectedForOtherAudienceIssuerTypeAfterExpiryOrUnknownKey() {
        String token = minter.mint(identity, weather, "r");
        assertRejected(verifierFor("billing-svc"), token);
        assertRejected(new Ed25519JwtVerifier(keys.publicJwks(), "someone-else", "weather-svc", TokenTypes.IDENTITY, Duration.ZERO, clock), token);
        assertRejected(new Ed25519JwtVerifier(keys.publicJwks(), "waypoint-gateway", "weather-svc", TokenTypes.CUSTOMER, Duration.ZERO, clock), token);
        OctetKeyPair stranger = GatewayKeyStore.generate();
        assertRejected(new Ed25519JwtVerifier(new com.nimbusds.jose.jwk.JWKSet(stranger.toPublicJWK()), "waypoint-gateway", "weather-svc",
                TokenTypes.IDENTITY, Duration.ZERO, clock), token);
        clock.advance(Duration.ofSeconds(91));
        assertRejected(verifierFor("weather-svc"), token);
    }

    @Test
    void tokenSignedWithAttackerKeyIsRejectedEvenIfItEmbedsTheKey() {
        OctetKeyPair attacker = GatewayKeyStore.generate();
        String forged = TestTokens.customer().clock(clock).key(attacker).type(TokenTypes.IDENTITY)
                .issuer("waypoint-gateway").audience("weather-svc").header(h -> h.jwk(attacker.toPublicJWK())).sign();
        assertRejected(verifierFor("weather-svc"), forged);
        assertThat(UpstreamVerifier.fixed(keys.publicJwks().toString(), "weather-svc").accepts(forged)).isFalse();
    }

    private static void assertRejected(Ed25519JwtVerifier verifier, String token) {
        try {
            verifier.verify(token);
            throw new AssertionError("token was accepted");
        } catch (TokenException expected) {
            // rejected
        }
    }
}
