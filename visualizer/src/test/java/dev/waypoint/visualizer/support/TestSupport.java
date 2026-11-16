package dev.waypoint.visualizer.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.visualizer.config.VisualizerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class TestSupport {

    public static final OctetKeyPair ISSUER_KEY = generate("issuer-test");
    public static final OctetKeyPair GATEWAY_KEY = generate("gateway-test");
    public static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    private TestSupport() {
    }

    public static OctetKeyPair generate(String kid) {
        try {
            return new OctetKeyPairGenerator(Curve.Ed25519).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    public static String jwks(OctetKeyPair... keys) {
        return new JWKSet(List.of(keys)).toPublicJWKSet().toString();
    }

    /** An identity token like the gateway mints for upstreams. */
    public static String identityToken(OctetKeyPair key, String tenant) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("waypoint-gateway").audience("weather-svc").subject("web-app").claim("tid", tenant)
                    .claim("scope", "weather:read").issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(60)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.Ed25519)
                    .type(new JOSEObjectType("gateway-identity+jwt")).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new Ed25519Signer(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static VisualizerProperties properties(String gatewayUrl, String upstreamAdminUrl, String jwksUrl) {
        return properties(gatewayUrl, upstreamAdminUrl, jwksUrl, Map.of(), "http://127.0.0.1:1/stats;csv",
                new VisualizerProperties.Access(false, "demo", ""));
    }

    public static VisualizerProperties properties(String gatewayUrl, String upstreamAdminUrl, String jwksUrl,
                                                  Map<String, String> management, String statsUrl,
                                                  VisualizerProperties.Access access) {
        return new VisualizerProperties(
                new VisualizerProperties.Issuer("", ISSUER_KEY.toJSONString(), "waypoint-issuer", "gateway"),
                Map.of("lb", gatewayUrl, "gateway-1", gatewayUrl, "gateway-2", gatewayUrl),
                "lb", management, 1, "HAProxy", "", statsUrl, upstreamAdminUrl, jwksUrl,
                List.of("acme", "globex", "initech", "loadtest", "umbrella"),
                Duration.ofMinutes(5), Duration.ofSeconds(5), 200, 20, access);
    }
}
