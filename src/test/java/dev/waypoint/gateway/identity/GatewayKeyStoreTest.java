package dev.waypoint.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import dev.waypoint.gateway.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void stagedKeyIsPublishedAtOnceButSignsOnlyFromActivation() {
        OctetKeyPair first = GatewayKeyStore.generate();
        OctetKeyPair second = GatewayKeyStore.generate();
        store.activate(first);
        Instant activateAt = clock.instant().plusSeconds(10);
        assertThat(store.stage(second, activateAt)).isEmpty();
        assertThat(kids()).containsExactly(first.getKeyID(), second.getKeyID());
        clock.advance(Duration.ofMillis(9999));
        assertThat(store.currentSigningKey().getKeyID()).isEqualTo(first.getKeyID());
        clock.advance(Duration.ofMillis(1));
        assertThat(store.currentSigningKey().getKeyID()).isEqualTo(second.getKeyID());
        assertThat(kids()).containsExactly(second.getKeyID(), first.getKeyID());
    }

    @Test
    void previousKeyRetentionCountsFromActivationNotFromStaging() {
        OctetKeyPair first = GatewayKeyStore.generate();
        OctetKeyPair second = GatewayKeyStore.generate();
        store.activate(first);
        store.stage(second, clock.instant().plusSeconds(10));
        clock.advance(Duration.ofSeconds(100));
        assertThat(kids()).containsExactly(second.getKeyID(), first.getKeyID());
        clock.advance(Duration.ofSeconds(1));
        assertThat(kids()).containsExactly(second.getKeyID());
    }

    @Test
    void restagingDropsTheEarlierStagedKeyThatNeverSigned() {
        OctetKeyPair first = GatewayKeyStore.generate();
        OctetKeyPair staged = GatewayKeyStore.generate();
        OctetKeyPair replacement = GatewayKeyStore.generate();
        store.activate(first);
        store.stage(staged, clock.instant().plusSeconds(10));
        clock.advance(Duration.ofSeconds(5));
        assertThat(store.stage(replacement, clock.instant().plusSeconds(10))).contains(staged);
        assertThat(kids()).containsExactly(first.getKeyID(), replacement.getKeyID());
        clock.advance(Duration.ofSeconds(9));
        assertThat(store.currentSigningKey().getKeyID()).as("first signs until the replacement activates").isEqualTo(first.getKeyID());
        clock.advance(Duration.ofSeconds(1));
        assertThat(store.currentSigningKey().getKeyID()).isEqualTo(replacement.getKeyID());
    }

    @Test
    void stagingAfterTheEarlierStagedKeyActivatedSupersedesNothing() {
        OctetKeyPair first = GatewayKeyStore.generate();
        OctetKeyPair second = GatewayKeyStore.generate();
        OctetKeyPair third = GatewayKeyStore.generate();
        store.activate(first);
        store.stage(second, clock.instant().plusSeconds(10));
        clock.advance(Duration.ofSeconds(10));
        assertThat(store.stage(third, clock.instant().plusSeconds(10))).isEmpty();
        assertThat(store.currentSigningKey().getKeyID()).isEqualTo(second.getKeyID());
    }

    @Test
    void zeroGraceStagingSignsImmediately() {
        OctetKeyPair first = GatewayKeyStore.generate();
        OctetKeyPair second = GatewayKeyStore.generate();
        store.activate(first);
        store.stage(second, clock.instant());
        assertThat(store.currentSigningKey().getKeyID()).isEqualTo(second.getKeyID());
    }

    @Test
    void stagingWithoutACurrentKeyActivatesWithNothingToRetire() {
        OctetKeyPair key = GatewayKeyStore.generate();
        store.stage(key, clock.instant().plusSeconds(1));
        assertThat(store.hasSigningKey()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(store.currentSigningKey().getKeyID()).isEqualTo(key.getKeyID());
        assertThat(kids()).containsExactly(key.getKeyID());
    }

    @Test
    void racingPromotionsActivateTheStagedKeyOnce() throws Exception {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        Thread[] slowReader = new Thread[1];
        Clock pausingClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                if (Thread.currentThread() == slowReader[0] && paused.getCount() > 0) {
                    paused.countDown();
                    await(resume);
                }
                return clock.instant();
            }
        };
        GatewayKeyStore racing = new GatewayKeyStore(pausingClock, Duration.ofSeconds(90));
        OctetKeyPair first = GatewayKeyStore.generate();
        OctetKeyPair second = GatewayKeyStore.generate();
        racing.activate(first);
        racing.stage(second, clock.instant().plusSeconds(1));
        clock.advance(Duration.ofSeconds(1));

        slowReader[0] = Thread.ofVirtual().unstarted(racing::currentSigningKey);
        slowReader[0].start();
        assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(racing.currentSigningKey().getKeyID()).isEqualTo(second.getKeyID());
        resume.countDown();
        slowReader[0].join(Duration.ofSeconds(10));

        assertThat(racing.currentSigningKey().getKeyID()).isEqualTo(second.getKeyID());
        assertThat(racing.ownPublishedKeys()).extracting(OctetKeyPair::getKeyID).containsExactly(second.getKeyID(), first.getKeyID());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
