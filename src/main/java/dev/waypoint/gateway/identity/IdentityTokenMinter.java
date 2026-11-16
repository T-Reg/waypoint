package dev.waypoint.gateway.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.gateway.auth.TenantIdentity;
import dev.waypoint.gateway.auth.TokenTypes;
import dev.waypoint.gateway.routing.Route;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Mints the per-request {@code X-Gateway-Identity} token, bound to one upstream via {@code aud}. */
public class IdentityTokenMinter {

    private final GatewayKeyStore keyStore;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;

    public IdentityTokenMinter(GatewayKeyStore keyStore, String issuer, Duration ttl, Clock clock) {
        this.keyStore = keyStore;
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = clock;
    }

    public String mint(TenantIdentity identity, Route route, String requestId) {
        OctetKeyPair key = keyStore.currentSigningKey();
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(route.audience())
                .subject(identity.subject())
                .claim("tid", identity.tenantId())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .jwtID(UUID.randomUUID().toString())
                .claim("rid", requestId);
        String scope = ScopeFilter.filter(identity.scopes(), route.effectiveForwardScopes());
        if (!scope.isEmpty()) {
            claims.claim("scope", scope);
        }
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.Ed25519).type(TokenTypes.IDENTITY).keyID(key.getKeyID()).build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(new Ed25519Signer(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign identity token", e);
        }
        return jwt.serialize();
    }
}
