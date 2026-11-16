package dev.waypoint.visualizer.traffic;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.visualizer.config.VisualizerProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Verifies identity tokens the way a real upstream would: keys from the running gateway's JWKS. */
@Component
public class IdentityVerifier {

    private static final Duration REFETCH_INTERVAL = Duration.ofSeconds(5);

    private final HttpClient http;
    private final URI jwksUri;
    private final Clock clock;
    private volatile JWKSet keys = new JWKSet();
    private volatile Instant lastFetch = Instant.MIN;

    public IdentityVerifier(HttpClient http, VisualizerProperties properties, Clock clock) {
        this.http = http;
        this.jwksUri = URI.create(properties.jwksUrl());
        this.clock = clock;
    }

    public RequestResult.Identity verify(String token) {
        SignedJWT jwt;
        Map<String, Object> claims;
        try {
            jwt = SignedJWT.parse(token);
            claims = new LinkedHashMap<>(jwt.getJWTClaimsSet().toJSONObject());
        } catch (ParseException e) {
            return new RequestResult.Identity(false, null, null, Map.of(), "not a JWT");
        }
        String kid = jwt.getHeader().getKeyID();
        String alg = jwt.getHeader().getAlgorithm().getName();
        if (!JWSAlgorithm.Ed25519.equals(jwt.getHeader().getAlgorithm())) {
            return new RequestResult.Identity(false, kid, alg, claims, "unexpected algorithm " + alg);
        }
        try {
            OctetKeyPair key = keyFor(kid);
            if (key == null) {
                return new RequestResult.Identity(false, kid, alg, claims, "kid not in gateway JWKS");
            }
            boolean valid = jwt.verify(new Ed25519Verifier(key));
            return new RequestResult.Identity(valid, kid, alg, claims, valid ? null : "signature mismatch");
        } catch (IOException | JOSEException e) {
            return new RequestResult.Identity(false, kid, alg, claims, "JWKS unavailable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RequestResult.Identity(false, kid, alg, claims, "interrupted");
        }
    }

    /** Refetches on an unknown kid (key rotation), at most once per interval. */
    private OctetKeyPair keyFor(String kid) throws IOException, InterruptedException {
        JWK key = keys.getKeyByKeyId(kid);
        Instant now = clock.instant();
        if (key == null && lastFetch.plus(REFETCH_INTERVAL).isBefore(now)) {
            lastFetch = now;
            keys = fetch();
            key = keys.getKeyByKeyId(kid);
        }
        return key instanceof OctetKeyPair okp ? okp : null;
    }

    private JWKSet fetch() throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("JWKS returned " + response.statusCode());
        }
        try {
            return JWKSet.parse(response.body());
        } catch (ParseException e) {
            throw new IOException("JWKS is not valid JSON", e);
        }
    }
}
