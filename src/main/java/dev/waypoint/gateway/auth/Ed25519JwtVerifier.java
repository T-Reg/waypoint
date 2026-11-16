package dev.waypoint.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies Ed25519 JWTs per RFC 8725: the algorithm is pinned, {@code typ} is explicit, and
 * {@code iss}, single-valued {@code aud}, {@code sub}, {@code exp} and {@code nbf} are required.
 * Keys come only from the configured set; {@code jku}, {@code x5u} and {@code jwk} headers are ignored.
 */
public final class Ed25519JwtVerifier {

    private final Map<String, Ed25519Verifier> verifiersByKid;
    private final Ed25519Verifier soleVerifier;
    private final String issuer;
    private final String audience;
    private final JOSEObjectType type;
    private final Duration clockSkew;
    private final Clock clock;

    public Ed25519JwtVerifier(JWKSet keys, String issuer, String audience, JOSEObjectType type, Duration clockSkew, Clock clock) {
        this.issuer = require(issuer, "issuer");
        this.audience = require(audience, "audience");
        this.type = type;
        this.clockSkew = clockSkew;
        this.clock = clock;
        Map<String, Ed25519Verifier> byKid = new HashMap<>();
        Ed25519Verifier last = null;
        for (JWK jwk : keys.getKeys()) {
            if (!(jwk instanceof OctetKeyPair okp) || !Curve.Ed25519.equals(okp.getCurve())) {
                throw new IllegalArgumentException("only Ed25519 OKP keys are supported");
            }
            try {
                last = new Ed25519Verifier(okp.toPublicJWK());
            } catch (JOSEException e) {
                throw new IllegalArgumentException("unusable Ed25519 key", e);
            }
            if (okp.getKeyID() != null) {
                byKid.put(okp.getKeyID(), last);
            }
        }
        if (last == null) {
            throw new IllegalArgumentException("at least one verification key is required");
        }
        this.verifiersByKid = Map.copyOf(byKid);
        this.soleVerifier = keys.getKeys().size() == 1 ? last : null;
    }

    public JWTClaimsSet verify(String token) throws TokenException {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw invalid("malformed token");
        }
        JWSHeader header = jwt.getHeader();
        if (!JWSAlgorithm.Ed25519.equals(header.getAlgorithm())) {
            throw invalid("unexpected alg");
        }
        if (header.getType() == null || !type.toString().equalsIgnoreCase(header.getType().toString())) {
            throw invalid("unexpected typ");
        }
        Ed25519Verifier verifier = header.getKeyID() != null ? verifiersByKid.get(header.getKeyID()) : soleVerifier;
        if (verifier == null) {
            throw invalid("unknown signing key");
        }
        try {
            if (!jwt.verify(verifier)) {
                throw invalid("bad signature");
            }
        } catch (JOSEException e) {
            throw invalid("bad signature");
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw invalid("malformed claims");
        }
        checkClaims(claims);
        return claims;
    }

    private void checkClaims(JWTClaimsSet claims) throws TokenException {
        if (!issuer.equals(claims.getIssuer())) {
            throw invalid("unexpected iss");
        }
        List<String> aud = claims.getAudience();
        if (aud.size() != 1 || !audience.equals(aud.get(0))) {
            throw invalid("unexpected aud");
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw invalid("missing sub");
        }
        Date exp = claims.getExpirationTime();
        Date nbf = claims.getNotBeforeTime();
        if (exp == null || nbf == null) {
            throw invalid("exp and nbf are required");
        }
        Instant now = clock.instant();
        if (now.isAfter(exp.toInstant().plus(clockSkew))) {
            throw new TokenException(TokenException.Reason.EXPIRED, "token expired");
        }
        if (now.plus(clockSkew).isBefore(nbf.toInstant())) {
            throw invalid("token not yet valid");
        }
    }

    private static TokenException invalid(String message) {
        return new TokenException(TokenException.Reason.INVALID, message);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
