package dev.waypoint.visualizer.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.visualizer.config.VisualizerProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Development stand-in for the token issuer, mirroring token-cli's claims, plus deliberate faults. */
@Component
public class TokenMinter {

    static final JOSEObjectType CUSTOMER_TYPE = new JOSEObjectType("customer+jwt");
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(2);
    private static final Duration FAULT_OFFSET = Duration.ofMinutes(5);

    private final OctetKeyPair key;
    private final String issuer;
    private final String audience;
    private final Set<String> allowedTenants;
    private final Duration ttl;
    private final Clock clock;

    public TokenMinter(VisualizerProperties properties, Clock clock) {
        this.key = loadKey(properties.issuer());
        this.issuer = properties.issuer().name();
        this.audience = properties.issuer().audience();
        this.allowedTenants = Set.copyOf(properties.allowedTenants());
        this.ttl = DEFAULT_TTL.compareTo(properties.maxTtl()) > 0 ? properties.maxTtl() : DEFAULT_TTL;
        this.clock = clock;
    }

    /** Empty for {@link TokenFault#MISSING}: the request is sent without a token. */
    public Optional<String> mint(String tenant, String subject, String scope, TokenFault fault) {
        if (!allowedTenants.contains(tenant)) {
            throw new IllegalArgumentException("tenant '" + tenant + "' is not in visualizer.allowed-tenants");
        }
        if (fault == TokenFault.MISSING) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Instant notBefore = switch (fault) {
            case EXPIRED -> now.minus(FAULT_OFFSET.multipliedBy(2));
            case NOT_YET_VALID -> now.plus(FAULT_OFFSET);
            default -> now;
        };
        Instant expiry = switch (fault) {
            case EXPIRED -> now.minus(FAULT_OFFSET);
            case NOT_YET_VALID -> now.plus(FAULT_OFFSET).plus(ttl);
            default -> now.plus(ttl);
        };
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(fault == TokenFault.WRONG_AUDIENCE ? "gateway-admin" : audience)
                .subject(subject)
                .issueTime(Date.from(notBefore))
                .notBeforeTime(Date.from(notBefore))
                .expirationTime(Date.from(expiry))
                .jwtID(UUID.randomUUID().toString())
                .claim("tid", tenant);
        if (scope != null && !scope.isBlank()) {
            claims.claim("scope", scope.strip());
        }
        try {
            return Optional.of(switch (fault) {
                case ALG_NONE -> unsigned(claims.build());
                case ALG_HS256 -> hmacWithPublicKey(claims.build());
                case TAMPERED -> tampered(claims.build(), tenant);
                default -> signed(claims.build());
            });
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign token", e);
        }
    }

    private String signed(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(header(JWSAlgorithm.Ed25519), claims);
        jwt.sign(new Ed25519Signer(key));
        return jwt.serialize();
    }

    private String unsigned(JWTClaimsSet claims) {
        String header = "{\"alg\":\"none\",\"typ\":\"" + CUSTOMER_TYPE + "\",\"kid\":\"" + key.getKeyID() + "\"}";
        return Base64URL.encode(header.getBytes(StandardCharsets.UTF_8)) + "."
                + Base64URL.encode(claims.toString().getBytes(StandardCharsets.UTF_8)) + ".";
    }

    private String hmacWithPublicKey(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(header(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(key.getX().decode()));
        return jwt.serialize();
    }

    /** Signs for one tenant, then rewrites tid in the payload so the signature no longer matches. */
    private String tampered(JWTClaimsSet claims, String tenant) throws JOSEException {
        String[] parts = signed(claims).split("\\.");
        JWTClaimsSet forged = new JWTClaimsSet.Builder(claims).claim("tid", tenant + "-forged").build();
        return parts[0] + "." + Base64URL.encode(forged.toString().getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
    }

    private JWSHeader header(JWSAlgorithm algorithm) {
        return new JWSHeader.Builder(algorithm).type(CUSTOMER_TYPE).keyID(key.getKeyID()).build();
    }

    static OctetKeyPair loadKey(VisualizerProperties.Issuer issuer) {
        String json = issuer.key();
        if (json == null || json.isBlank()) {
            try {
                json = Files.readString(Path.of(issuer.keyFile()));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read issuer key file " + issuer.keyFile(), e);
            }
        }
        try {
            if (JWK.parse(json) instanceof OctetKeyPair okp && okp.isPrivate()) {
                return okp;
            }
        } catch (ParseException e) {
            throw new IllegalArgumentException("issuer key is not a JWK", e);
        }
        throw new IllegalArgumentException("issuer key must be a private Ed25519 JWK");
    }
}
