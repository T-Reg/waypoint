package dev.waypoint.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import dev.waypoint.gateway.store.Migrator;
import dev.waypoint.gateway.store.PostgresConfigStore;
import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.Db;
import dev.waypoint.gateway.support.MutableClock;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Key registration and rotation against a real PostgreSQL: peers are notified, and rotated keys publish before they sign. */
class KeyServiceIT {

    private static final Duration GRACE = Duration.ofSeconds(10);
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Duration SKEW = Duration.ofSeconds(30);

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private Db db;
    private KeyRegistry registry;
    private GatewayKeyStore keyStore;
    private KeyService service;

    @BeforeEach
    void setUp() {
        String url = Containers.newDatabase();
        db = new Db(url);
        new Migrator(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword(), "waypoint_app").migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
        registry = new KeyRegistry(JdbcClient.create(ds), PostgresConfigStore.CHANNEL);
        keyStore = new GatewayKeyStore(clock, TTL.plus(SKEW));
        service = newService("a", GRACE);
    }

    @Test
    void registeringAKeyNotifiesPeersAfterTheRowIsCommitted() throws Exception {
        try (Connection listener = db.connect(); Statement s = listener.createStatement()) {
            s.execute("LISTEN " + PostgresConfigStore.CHANNEL);
            service.initialize(0);
            String kid = service.currentKid();
            assertThat(payloads(listener)).containsExactly(KeyRegistry.NOTIFY_PREFIX + kid);
            assertThat(db.query("SELECT kid FROM gateway_keys WHERE kid = ?", kid)).hasSize(1);
        }
    }

    @Test
    void failedRegistrationSendsNoNotification() throws Exception {
        OctetKeyPair key = GatewayKeyStore.generate();
        OctetKeyPair other = GatewayKeyStore.generate();
        registry.register(key, "a", clock.instant());
        try (Connection listener = db.connect(); Statement s = listener.createStatement()) {
            s.execute("LISTEN " + PostgresConfigStore.CHANNEL);
            assertThatThrownBy(() -> registry.register(key, "a", clock.instant())).hasMessageContaining("duplicate key");
            registry.register(other, "a", clock.instant());
            assertThat(payloads(listener)).containsExactly(KeyRegistry.NOTIFY_PREFIX + other.getKeyID());
        }
    }

    @Test
    void rotationNotifiesAndPublishesTheNewKeyToPeersBeforeItSigns() throws Exception {
        service.initialize(0);
        String oldKid = service.currentKid();
        try (Connection listener = db.connect(); Statement s = listener.createStatement()) {
            s.execute("LISTEN " + PostgresConfigStore.CHANNEL);
            assertThat(service.applyGeneration(1)).isTrue();
            KeyService.Rotation rotation = service.lastRotation();
            assertThat(payloads(listener)).containsExactly(KeyRegistry.NOTIFY_PREFIX + rotation.kid());
            assertThat(rotation.activatesAt()).isEqualTo(clock.instant().plus(GRACE));
            assertThat(service.currentKid()).as("old key still signs during the grace").isEqualTo(oldKid);
            assertThat(peerKids()).contains(oldKid, rotation.kid());

            clock.advance(GRACE);
            assertThat(service.currentKid()).isEqualTo(rotation.kid());
        }
    }

    @Test
    void previousKeyIsRetiredInTheRegistryAtActivationSoPeersKeepItForTheFullTtl() {
        service.initialize(0);
        String oldKid = service.currentKid();
        service.applyGeneration(1);
        assertThat(retiredAt(oldKid)).isEqualTo(clock.instant().plus(GRACE));
        clock.advance(GRACE.plus(TTL).plus(SKEW).minusSeconds(1));
        assertThat(peerKids()).contains(oldKid);
        clock.advance(Duration.ofSeconds(1));
        assertThat(peerKids()).doesNotContain(oldKid);
    }

    @Test
    void rerotatingDuringTheGraceRetiresTheNeverUsedKeyAndExtendsThePreviousOne() {
        service.initialize(0);
        String oldKid = service.currentKid();
        service.applyGeneration(1);
        String abandonedKid = service.lastRotation().kid();
        clock.advance(Duration.ofSeconds(4));
        service.applyGeneration(2);
        String finalKid = service.lastRotation().kid();

        assertThat(retiredAt(abandonedKid)).isEqualTo(clock.instant());
        assertThat(retiredAt(oldKid)).isEqualTo(clock.instant().plus(GRACE));
        assertThat(service.currentKid()).isEqualTo(oldKid);
        clock.advance(GRACE);
        assertThat(service.currentKid()).isEqualTo(finalKid);
    }

    @Test
    void retirementTimeNeverMovesEarlier() {
        OctetKeyPair key = GatewayKeyStore.generate();
        Instant now = clock.instant();
        registry.register(key, "a", now);
        registry.retire(key.getKeyID(), now.plusSeconds(10));
        registry.retire(key.getKeyID(), now);
        assertThat(retiredAt(key.getKeyID())).isEqualTo(now.plusSeconds(10));
    }

    @Test
    void negativeGraceIsRejected() {
        assertThatThrownBy(() -> newService("b", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key-publish-grace");
    }

    private KeyService newService(String instanceId, Duration grace) {
        return new KeyService(keyStore, registry, clock, instanceId, TTL, SKEW, Duration.ofSeconds(10), Duration.ofMinutes(10), grace);
    }

    private List<String> peerKids() {
        Instant now = clock.instant();
        return registry.liveKeys(now.minus(TTL.plus(SKEW)), now.minus(Duration.ofMinutes(10)), now.minus(Duration.ofSeconds(30)))
                .keys().stream().map(JWK::getKeyID).toList();
    }

    private Instant retiredAt(String kid) {
        Object retiredAt = db.query("SELECT retired_at FROM gateway_keys WHERE kid = ?", kid).get(0).get("retired_at");
        return retiredAt instanceof OffsetDateTime odt ? odt.toInstant() : ((Timestamp) retiredAt).toInstant();
    }

    private static List<String> payloads(Connection listener) throws Exception {
        PGNotification[] notifications = listener.unwrap(PGConnection.class).getNotifications(1000);
        return notifications == null ? List.of() : Arrays.stream(notifications).map(PGNotification::getParameter).toList();
    }
}
