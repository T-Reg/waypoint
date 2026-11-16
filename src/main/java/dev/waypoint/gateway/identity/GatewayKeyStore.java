package dev.waypoint.gateway.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This instance's Ed25519 identity-token keys, held only in memory. A retired key stays published
 * for the token TTL plus clock skew so tokens it signed keep verifying. Peer instances' public keys
 * are merged in from the registry as a last-known-good set.
 */
public class GatewayKeyStore {

    private record RetiredKey(OctetKeyPair key, Instant retiredAt) {}

    private final Clock clock;
    private final Duration retention;
    private volatile OctetKeyPair current;
    private final List<RetiredKey> retired = new ArrayList<>();
    private volatile Map<String, JWK> peerKeys = Map.of();

    public GatewayKeyStore(Clock clock, Duration retention) {
        this.clock = clock;
        this.retention = retention;
    }

    public static OctetKeyPair generate() {
        try {
            return new OctetKeyPairGenerator(Curve.Ed25519)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.Ed25519)
                    .keyIDFromThumbprint(true)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate Ed25519 key", e);
        }
    }

    public OctetKeyPair currentSigningKey() {
        OctetKeyPair key = current;
        if (key == null) {
            throw new IllegalStateException("no signing key yet");
        }
        return key;
    }

    public boolean hasSigningKey() {
        return current != null;
    }

    /** Starts signing with {@code next}; call only after its public half is published. */
    public synchronized void activate(OctetKeyPair next) {
        if (current != null) {
            retired.add(new RetiredKey(current, clock.instant()));
        }
        current = next;
    }

    public synchronized void retireAll() {
        if (current != null) {
            retired.add(new RetiredKey(current, clock.instant()));
            current = null;
        }
    }

    public synchronized List<OctetKeyPair> ownPublishedKeys() {
        pruneRetired();
        List<OctetKeyPair> keys = new ArrayList<>();
        if (current != null) {
            keys.add(current);
        }
        retired.forEach(r -> keys.add(r.key()));
        return keys;
    }

    public void replacePeerKeys(Collection<JWK> keys) {
        Map<String, JWK> byKid = new LinkedHashMap<>();
        keys.forEach(k -> byKid.put(k.getKeyID(), k.toPublicJWK()));
        peerKeys = Map.copyOf(byKid);
    }

    /** Own live keys plus peers' keys; public parameters only. */
    public JWKSet publicJwks() {
        Map<String, JWK> keys = new LinkedHashMap<>();
        for (OctetKeyPair own : ownPublishedKeys()) {
            keys.put(own.getKeyID(), own.toPublicJWK());
        }
        peerKeys.forEach(keys::putIfAbsent);
        return new JWKSet(new ArrayList<>(keys.values()));
    }

    private void pruneRetired() {
        Instant cutoff = clock.instant().minus(retention);
        retired.removeIf(r -> r.retiredAt().isBefore(cutoff));
    }
}
