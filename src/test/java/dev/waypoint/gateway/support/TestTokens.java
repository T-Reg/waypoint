package dev.waypoint.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.gateway.auth.TokenTypes;
import dev.waypoint.gateway.identity.GatewayKeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Test-only token issuer mirroring token-cli, with knobs to produce every malformed variant. */
public final class TestTokens {

    public static final OctetKeyPair ISSUER_KEY = GatewayKeyStore.generate();
    public static final OctetKeyPair ADMIN_KEY = GatewayKeyStore.generate();
    public static final String CUSTOMER_ISSUER = "waypoint-issuer";
    public static final String ADMIN_ISSUER = "waypoint-admin-issuer";

    private TestTokens() {}

    public static String customer(String tenant, String sub, String scope) {
        return customer().tenant(tenant).subject(sub).scope(scope).sign();
    }

    public static String admin(String sub, String scope) {
        return admin().subject(sub).scope(scope).sign();
    }

    public static Builder customer() {
        return new Builder(ISSUER_KEY, TokenTypes.CUSTOMER, CUSTOMER_ISSUER, "gateway").tenant("acme").subject("web-app");
    }

    public static Builder admin() {
        return new Builder(ADMIN_KEY, TokenTypes.ADMIN, ADMIN_ISSUER, "gateway-admin").subject("alice");
    }

    public static final class Builder {
        private OctetKeyPair key;
        private JOSEObjectType type;
        private JWSAlgorithm alg = JWSAlgorithm.Ed25519;
        private Clock clock = Clock.systemUTC();
        private Duration ttl = Duration.ofMinutes(5);
        private Duration nbfOffset = Duration.ZERO;
        private boolean includeKid = true;
        private final Map<String, Object> claims = new LinkedHashMap<>();
        private Consumer<JWSHeader.Builder> headerCustomizer = h -> {};

        Builder(OctetKeyPair key, JOSEObjectType type, String issuer, String audience) {
            this.key = key;
            this.type = type;
            claims.put("iss", issuer);
            claims.put("aud", audience);
        }

        public Builder key(OctetKeyPair key) { this.key = key; return this; }
        public Builder type(JOSEObjectType type) { this.type = type; return this; }
        public Builder alg(JWSAlgorithm alg) { this.alg = alg; return this; }
        public Builder clock(Clock clock) { this.clock = clock; return this; }
        public Builder ttl(Duration ttl) { this.ttl = ttl; return this; }
        public Builder notBeforeOffset(Duration offset) { this.nbfOffset = offset; return this; }
        public Builder withoutKid() { this.includeKid = false; return this; }
        public Builder issuer(Object issuer) { return claim("iss", issuer); }
        public Builder audience(Object audience) { return claim("aud", audience); }
        public Builder tenant(Object tenant) { return claim("tid", tenant); }
        public Builder subject(Object subject) { return claim("sub", subject); }
        public Builder scope(Object scope) { return claim("scope", scope); }
        public Builder header(Consumer<JWSHeader.Builder> customizer) { this.headerCustomizer = customizer; return this; }

        public Builder claim(String name, Object value) {
            if (value == null) {
                claims.remove(name);
            } else {
                claims.put(name, value);
            }
            return this;
        }

        public Builder without(String name) {
            claims.remove(name);
            return this;
        }

        public String sign() {
            Instant now = clock.instant();
            Map<String, Object> all = new LinkedHashMap<>(claims);
            all.putIfAbsent("iat", now.getEpochSecond());
            all.putIfAbsent("nbf", now.plus(nbfOffset).getEpochSecond());
            all.putIfAbsent("exp", now.plus(ttl).getEpochSecond());
            all.putIfAbsent("jti", UUID.randomUUID().toString());
            all.values().removeIf(v -> v == OMIT);
            JWSHeader.Builder header = new JWSHeader.Builder(alg);
            if (type != null) {
                header.type(type);
            }
            if (includeKid) {
                header.keyID(key.getKeyID());
            }
            headerCustomizer.accept(header);
            try {
                return RawJwt.signEd25519(header.build(), all, key);
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Sentinel to drop a default claim such as {@code exp} or {@code nbf}. */
    public static final Object OMIT = new Object();

    /** Signs arbitrary JSON claims (including invalid types) with Ed25519. */
    public static final class RawJwt {
        private RawJwt() {}

        static String signEd25519(JWSHeader header, Map<String, Object> claims, OctetKeyPair key) throws JOSEException {
            com.nimbusds.jose.JWSObject jws = new com.nimbusds.jose.JWSObject(header,
                    new com.nimbusds.jose.Payload(com.nimbusds.jose.util.JSONObjectUtils.toJSONString(claims)));
            jws.sign(new Ed25519Signer(key));
            return jws.serialize();
        }
    }

    public static SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }
}
