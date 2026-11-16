package dev.waypoint.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.gateway.identity.GatewayKeyStore;
import dev.waypoint.gateway.support.MutableClock;
import dev.waypoint.gateway.support.TestTokens;
import java.net.URI;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class Ed25519JwtVerifierTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final Ed25519JwtVerifier verifier = new Ed25519JwtVerifier(new JWKSet(TestTokens.ISSUER_KEY.toPublicJWK()),
            "waypoint-issuer", "gateway", TokenTypes.CUSTOMER, Duration.ofSeconds(30), clock);

    private TestTokens.Builder token() {
        return TestTokens.customer().clock(clock);
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOfSatisfying(TokenException.class, e -> assertThat(e.reason()).isEqualTo(TokenException.Reason.INVALID));
    }

    @Test
    void acceptsValidToken() throws TokenException {
        JWTClaimsSet claims = verifier.verify(token().scope("weather:read").sign());
        assertThat(claims.getSubject()).isEqualTo("web-app");
        assertThat(claims.getClaim("tid")).isEqualTo("acme");
    }

    @Test
    void acceptsTokenWithoutKidWhenOnlyOneKey() throws TokenException {
        assertThat(verifier.verify(token().withoutKid().sign()).getSubject()).isEqualTo("web-app");
    }

    @Test
    void rejectsMalformedTokens() {
        assertInvalid("not-a-jwt");
        assertInvalid("a.b.c");
        assertInvalid("");
    }

    @Test
    void rejectsAlgNone() {
        String unsecured = new PlainJWT(new JWTClaimsSet.Builder().issuer("waypoint-issuer").audience("gateway")
                .subject("x").claim("tid", "acme").expirationTime(Date.from(clock.instant().plusSeconds(60)))
                .notBeforeTime(Date.from(clock.instant())).build()).serialize();
        assertInvalid(unsecured);
        assertInvalid(unsecured + "c2ln");
    }

    @Test
    void rejectsHs256EvenWithPublicKeyBytesAsSecret() throws Exception {
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(TokenTypes.CUSTOMER).build(),
                new JWTClaimsSet.Builder().issuer("waypoint-issuer").audience("gateway").subject("x").claim("tid", "acme")
                        .expirationTime(Date.from(clock.instant().plusSeconds(60))).notBeforeTime(Date.from(clock.instant())).build());
        byte[] secret = new byte[32];
        System.arraycopy(TestTokens.ISSUER_KEY.getDecodedX(), 0, secret, 0, 32);
        hmac.sign(new MACSigner(secret));
        assertInvalid(hmac.serialize());
    }

    @Test
    void rejectsDeprecatedPolymorphicEdDsaAlg() {
        assertInvalid(token().alg(JWSAlgorithm.EdDSA).sign());
    }

    @Test
    void rejectsWrongOrMissingTyp() {
        assertInvalid(token().type(TokenTypes.ADMIN).sign());
        assertInvalid(token().type(TokenTypes.IDENTITY).sign());
        assertInvalid(token().type(JOSEObjectType.JWT).sign());
        assertInvalid(token().type(null).sign());
    }

    @Test
    void typComparisonIsCaseInsensitive() throws TokenException {
        assertThat(verifier.verify(token().type(new JOSEObjectType("Customer+JWT")).sign())).isNotNull();
    }

    @Test
    void rejectsBadSignatureAndUnknownKeys() {
        String valid = token().sign();
        String[] parts = valid.split("\\.");
        byte[] sig = Base64URL.from(parts[2]).decode();
        sig[0] ^= 1;
        assertInvalid(parts[0] + "." + parts[1] + "." + Base64URL.encode(sig));
        OctetKeyPair other = GatewayKeyStore.generate();
        assertInvalid(token().key(other).withoutKid().sign());
        assertInvalid(token().key(other).sign());
    }

    @Test
    void rejectsTamperedPayload() {
        String valid = token().sign();
        String[] parts = valid.split("\\.");
        String forged = Base64URL.encode("{\"iss\":\"waypoint-issuer\",\"aud\":\"gateway\",\"sub\":\"x\",\"tid\":\"globex\",\"exp\":9999999999,\"nbf\":0}").toString();
        assertInvalid(parts[0] + "." + forged + "." + parts[2]);
    }

    @Test
    void ignoresEmbeddedKeyHeaders() throws Exception {
        OctetKeyPair attacker = GatewayKeyStore.generate();
        assertInvalid(token().key(attacker).header(h -> h.jwk(attacker.toPublicJWK())).sign());
        assertInvalid(token().key(attacker).withoutKid().header(h -> h.jwkURL(URI.create("https://attacker.example/jwks"))).sign());
        assertInvalid(token().key(attacker).withoutKid().header(h -> h.x509CertURL(URI.create("https://attacker.example/cert"))).sign());
    }

    @Test
    void rejectsWrongIssuer() {
        assertInvalid(token().issuer("someone-else").sign());
        assertInvalid(token().without("iss").sign());
    }

    @Test
    void rejectsMultiValuedIssuer() {
        assertInvalid(token().issuer(List.of("waypoint-issuer", "other")).sign());
    }

    @Test
    void rejectsWrongOrMultiValuedAudience() {
        assertInvalid(token().audience("gateway-admin").sign());
        assertInvalid(token().audience(List.of("gateway", "other")).sign());
        assertInvalid(token().without("aud").sign());
    }

    @Test
    void acceptsSingleElementAudienceArray() throws TokenException {
        assertThat(verifier.verify(token().audience(List.of("gateway")).sign())).isNotNull();
    }

    @Test
    void rejectsMissingSubject() {
        assertInvalid(token().without("sub").sign());
        assertInvalid(token().subject(" ").sign());
    }

    @Test
    void rejectsExpiredBeyondSkew() {
        String expired = token().ttl(Duration.ofSeconds(60)).sign();
        clock.advance(Duration.ofSeconds(89));
        assertThat(catchReason(expired)).isNull();
        clock.advance(Duration.ofSeconds(2));
        assertThat(catchReason(expired)).isEqualTo(TokenException.Reason.EXPIRED);
    }

    @Test
    void rejectsNotYetValidBeyondSkew() {
        assertThat(catchReason(token().notBeforeOffset(Duration.ofSeconds(29)).sign())).isNull();
        assertThat(catchReason(token().notBeforeOffset(Duration.ofSeconds(31)).sign())).isEqualTo(TokenException.Reason.INVALID);
    }

    @Test
    void requiresExpAndNbf() {
        assertInvalid(token().claim("exp", TestTokens.OMIT).sign());
        assertInvalid(token().claim("nbf", TestTokens.OMIT).sign());
    }

    @Test
    void rejectsMalformedClaimTypes() {
        assertInvalid(token().claim("exp", "tomorrow").sign());
    }

    @Test
    void constructorRejectsUnsupportedKeys() throws Exception {
        JWKSet ec = new JWKSet(new ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).generate().toPublicJWK());
        assertThatThrownBy(() -> new Ed25519JwtVerifier(ec, "i", "a", TokenTypes.CUSTOMER, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        OctetKeyPair x25519 = new com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator(Curve.X25519).generate();
        assertThatThrownBy(() -> new Ed25519JwtVerifier(new JWKSet(x25519.toPublicJWK()), "i", "a", TokenTypes.CUSTOMER, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ed25519JwtVerifier(new JWKSet(), "i", "a", TokenTypes.CUSTOMER, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        JWKSet ok = new JWKSet(TestTokens.ISSUER_KEY.toPublicJWK());
        assertThatThrownBy(() -> new Ed25519JwtVerifier(ok, " ", "a", TokenTypes.CUSTOMER, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ed25519JwtVerifier(ok, "i", null, TokenTypes.CUSTOMER, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectsKeyByKidAcrossRotation() throws TokenException {
        OctetKeyPair second = GatewayKeyStore.generate();
        Ed25519JwtVerifier both = new Ed25519JwtVerifier(new JWKSet(List.of(TestTokens.ISSUER_KEY.toPublicJWK(), second.toPublicJWK())),
                "waypoint-issuer", "gateway", TokenTypes.CUSTOMER, Duration.ofSeconds(30), clock);
        assertThat(both.verify(token().key(second).sign())).isNotNull();
        assertThat(both.verify(token().sign())).isNotNull();
        assertThatThrownBy(() -> both.verify(token().withoutKid().sign())).isInstanceOf(TokenException.class);
    }

    private TokenException.Reason catchReason(String token) {
        try {
            verifier.verify(token);
            return null;
        } catch (TokenException e) {
            return e.reason();
        }
    }
}
