package dev.waypoint.visualizer.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.support.TestSupport;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenMinterTest {

    private final TokenMinter minter = new TokenMinter(TestSupport.properties("http://gw", "http://up", "http://jwks"),
            TestSupport.fixedClock());

    private SignedJWT mint(TokenFault fault) throws Exception {
        return SignedJWT.parse(minter.mint("acme", "web-app", "weather:read maps:read", fault).orElseThrow());
    }

    @Test
    void validTokenMatchesWhatTheGatewayExpects() throws Exception {
        SignedJWT jwt = mint(TokenFault.NONE);

        assertThat(jwt.verify(new Ed25519Verifier(TestSupport.ISSUER_KEY.toPublicJWK()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.Ed25519);
        assertThat(jwt.getHeader().getType().getType()).isEqualTo("customer+jwt");
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("issuer-test");
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("waypoint-issuer");
        assertThat(claims.getAudience()).containsExactly("gateway");
        assertThat(claims.getSubject()).isEqualTo("web-app");
        assertThat(claims.getStringClaim("tid")).isEqualTo("acme");
        assertThat(claims.getStringClaim("scope")).isEqualTo("weather:read maps:read");
        assertThat(claims.getNotBeforeTime()).isEqualTo(Date.from(TestSupport.NOW));
        assertThat(claims.getExpirationTime()).isEqualTo(Date.from(TestSupport.NOW.plus(Duration.ofMinutes(2))));
        assertThat(claims.getJWTID()).isNotBlank();
    }

    @Test
    void ttlNeverExceedsTheConfiguredMaximum() throws Exception {
        VisualizerProperties base = TestSupport.properties("http://gw", "http://up", "http://jwks");
        VisualizerProperties shortTtl = new VisualizerProperties(base.issuer(), base.targets(), base.defaultTarget(),
                base.gatewayManagement(), base.managementProbes(), base.loadBalancerName(), base.loadBalancerHealthUrl(), base.haproxyStatsUrl(), base.upstreamAdminUrl(), base.jwksUrl(),
                base.allowedTenants(), Duration.ofSeconds(30), base.requestTimeout(), base.maxRequestsPerRun(),
                base.maxConcurrency(), base.access());
        SignedJWT jwt = SignedJWT.parse(new TokenMinter(shortTtl, TestSupport.fixedClock())
                .mint("acme", "a", null, TokenFault.NONE).orElseThrow());

        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isEqualTo(Date.from(TestSupport.NOW.plusSeconds(30)));
    }

    @Test
    void blankScopeIsOmitted() throws Exception {
        SignedJWT jwt = SignedJWT.parse(minter.mint("acme", "a", "  ", TokenFault.NONE).orElseThrow());

        assertThat(jwt.getJWTClaimsSet().getClaim("scope")).isNull();
    }

    @Test
    void missingFaultSendsNoToken() {
        assertThat(minter.mint("acme", "a", "x", TokenFault.MISSING)).isEmpty();
    }

    @Test
    void expiredTokenIsSignedButPastExpiry() throws Exception {
        SignedJWT jwt = mint(TokenFault.EXPIRED);

        assertThat(jwt.verify(new Ed25519Verifier(TestSupport.ISSUER_KEY.toPublicJWK()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isBefore(Date.from(TestSupport.NOW));
    }

    @Test
    void notYetValidTokenStartsInTheFuture() throws Exception {
        SignedJWT jwt = mint(TokenFault.NOT_YET_VALID);

        assertThat(jwt.getJWTClaimsSet().getNotBeforeTime()).isAfter(Date.from(TestSupport.NOW));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isAfter(jwt.getJWTClaimsSet().getNotBeforeTime());
    }

    @Test
    void tamperedTokenClaimsAnotherTenantAndFailsVerification() throws Exception {
        SignedJWT jwt = mint(TokenFault.TAMPERED);

        assertThat(jwt.getJWTClaimsSet().getStringClaim("tid")).isEqualTo("acme-forged");
        assertThat(jwt.verify(new Ed25519Verifier(TestSupport.ISSUER_KEY.toPublicJWK()))).isFalse();
    }

    @Test
    void wrongAudienceIsTheAdminAudience() throws Exception {
        SignedJWT jwt = mint(TokenFault.WRONG_AUDIENCE);

        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("gateway-admin");
        assertThat(jwt.verify(new Ed25519Verifier(TestSupport.ISSUER_KEY.toPublicJWK()))).isTrue();
    }

    @Test
    void algNoneHasNoSignature() {
        String token = minter.mint("acme", "a", "x", TokenFault.ALG_NONE).orElseThrow();
        String[] parts = token.split("\\.", -1);

        assertThat(parts).hasSize(3);
        assertThat(parts[2]).isEmpty();
        assertThat(new String(new Base64URL(parts[0]).decode(), StandardCharsets.UTF_8)).contains("\"alg\":\"none\"");
    }

    @Test
    void hs256IsKeyedWithTheIssuerPublicKey() throws Exception {
        SignedJWT jwt = mint(TokenFault.ALG_HS256);

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(TestSupport.ISSUER_KEY.getX().decode()))).isTrue();
    }

    @Test
    void refusesTenantsOutsideTheAllowlist() {
        assertThatThrownBy(() -> minter.mint("evil-corp", "a", "x", TokenFault.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-tenants");
    }

    @Test
    void loadsKeyFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("issuer.private.jwk.json");
        Files.writeString(file, TestSupport.ISSUER_KEY.toJSONString());

        assertThat(TokenMinter.loadKey(new VisualizerProperties.Issuer(file.toString(), "", "i", "a")).getKeyID())
                .isEqualTo("issuer-test");
        assertThat(TokenMinter.loadKey(new VisualizerProperties.Issuer(file.toString(), null, "i", "a")).isPrivate()).isTrue();
    }

    @Test
    void rejectsUnusableKeys(@TempDir Path dir) {
        assertThatThrownBy(() -> TokenMinter.loadKey(new VisualizerProperties.Issuer(dir.resolve("missing").toString(), "", "i", "a")))
                .isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> TokenMinter.loadKey(new VisualizerProperties.Issuer("", "not json", "i", "a")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a JWK");
        for (String key : List.of(TestSupport.ISSUER_KEY.toPublicJWK().toJSONString(),
                "{\"kty\":\"oct\",\"k\":\"c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0MTI\"}")) {
            assertThatThrownBy(() -> TokenMinter.loadKey(new VisualizerProperties.Issuer("", key, "i", "a")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private Ed25519");
        }
    }

    @Test
    void everyFaultHasADescription() {
        for (TokenFault fault : TokenFault.values()) {
            assertThat(fault.description()).isNotBlank();
        }
    }
}
