package dev.waypoint.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import dev.waypoint.gateway.support.MutableClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayKeyStoreTest {

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final GatewayKeyStore store = new GatewayKeyStore(clock, Duration.ofSeconds(90));

    @Test
    void noKeyUntilActivated() {
        assertThat(store.hasSigningKey()).isFalse();
        assertThatThrownBy(store::currentSigningKey).isInstanceOf(IllegalStateException.class);
        assertThat(store.publicJwks().getKeys()).isEmpty();
    }

    @Test
    void jwksContainsOnlyPublicParameters() throws Exception {
        OctetKeyPair key = GatewayKeyStore.generate();
        store.activate(key);
        String json = store.publicJwks().toString(true);
        JsonNode keys = new ObjectMapper().readTree(json).path("keys");
        assertThat(keys).hasSize(1);
        for (JsonNode k : keys) {
            assertThat(k.has("d")).isFalse();
            assertThat(k.path("kty").asString()).isEqualTo("OKP");
            assertThat(k.path("crv").asString()).isEqualTo("Ed25519");
            assertThat(k.path("alg").asString()).isEqualTo("Ed25519");
            assertThat(k.path("use").asString()).isEqualTo("sig");
            assertThat(k.path("kid").asString()).isEqualTo(key.getKeyID());
            assertThat(k.path("x").asString()).isNotBlank();
        }
        assertThat(json).doesNotContain(key.getD().toString());
    }

    @Test
    void rotationPublishesBothThenRetiresOldAfterTtlPlusSkew() {
        OctetKeyPair first = GatewayKeyStore.generate();
        OctetKeyPair second = GatewayKeyStore.generate();
        store.activate(first);
        clock.advance(Duration.ofSeconds(5));
        store.activate(second);
        assertThat(store.currentSigningKey().getKeyID()).isEqualTo(second.getKeyID());
        assertThat(kids()).containsExactly(second.getKeyID(), first.getKeyID());
        clock.advance(Duration.ofSeconds(90));
        assertThat(kids()).containsExactly(second.getKeyID(), first.getKeyID());
        clock.advance(Duration.ofSeconds(1));
        assertThat(kids()).containsExactly(second.getKeyID());
    }

    @Test
    void retireAllKeepsKeysPublishedDuringOverlap() {
        OctetKeyPair key = GatewayKeyStore.generate();
        store.activate(key);
        store.retireAll();
        store.retireAll();
        assertThat(store.hasSigningKey()).isFalse();
        assertThat(kids()).containsExactly(key.getKeyID());
        clock.advance(Duration.ofSeconds(91));
        assertThat(kids()).isEmpty();
    }

    @Test
    void mergesPeerKeysPublicOnlyWithoutDuplicates() {
        OctetKeyPair own = GatewayKeyStore.generate();
        OctetKeyPair peer = GatewayKeyStore.generate();
        store.activate(own);
        store.replacePeerKeys(List.of(peer, own.toPublicJWK()));
        assertThat(kids()).containsExactly(own.getKeyID(), peer.getKeyID());
        assertThat(store.publicJwks().getKeys()).noneMatch(JWK::isPrivate);
    }

    private List<String> kids() {
        return store.publicJwks().getKeys().stream().map(JWK::getKeyID).toList();
    }
}
