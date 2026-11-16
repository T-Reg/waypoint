package dev.waypoint.gateway.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.List;
import java.util.Set;

/**
 * Verifies X-Gateway-Identity the way a real Java upstream would: keys from a fixed JWKS URL
 * (cached, refetched on unknown {@code kid}), alg pinned to Ed25519, then type and claim checks.
 * Nimbus' default processor can't build Ed25519 verifiers, so key selection is explicit here.
 */
public final class UpstreamVerifier {

    private final JWKSource<SecurityContext> keys;
    private final DefaultJOSEObjectTypeVerifier<SecurityContext> typeVerifier =
            new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("identity+jwt"));
    private final DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier;

    private UpstreamVerifier(JWKSource<SecurityContext> keys, String audience) {
        this.keys = keys;
        this.claimsVerifier = new DefaultJWTClaimsVerifier<>(audience,
                new JWTClaimsSet.Builder().issuer("waypoint-gateway").build(),
                Set.of("sub", "tid", "exp", "iat", "jti", "rid"));
    }

    public static UpstreamVerifier remote(int managementPort, String audience) {
        try {
            JWKSource<SecurityContext> source = JWKSourceBuilder
                    .create(URI.create("http://127.0.0.1:" + managementPort + "/.well-known/jwks.json").toURL())
                    .retrying(false)
                    .build();
            return new UpstreamVerifier(source, audience);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static UpstreamVerifier fixed(String jwksJson, String audience) {
        try {
            return new UpstreamVerifier(new ImmutableJWKSet<>(JWKSet.parse(jwksJson)), audience);
        } catch (ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    public JWTClaimsSet verify(String token) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        if (!JWSAlgorithm.Ed25519.equals(jwt.getHeader().getAlgorithm())) {
            throw new BadJOSEException("unexpected alg");
        }
        typeVerifier.verify(jwt.getHeader().getType(), null);
        List<JWK> candidates = keys.get(new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader())), null);
        if (candidates.isEmpty()) {
            throw new BadJOSEException("no matching key");
        }
        boolean verified = false;
        for (JWK candidate : candidates) {
            if (candidate instanceof OctetKeyPair okp && jwt.verify(new Ed25519Verifier(okp.toPublicJWK()))) {
                verified = true;
                break;
            }
        }
        if (!verified) {
            throw new BadJOSEException("bad signature");
        }
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        claimsVerifier.verify(claims, null);
        return claims;
    }

    public boolean accepts(String token) {
        try {
            verify(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
