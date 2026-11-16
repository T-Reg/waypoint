package dev.waypoint.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import dev.waypoint.gateway.routing.RouteValidator;
import dev.waypoint.gateway.routing.UpstreamAddressPolicy;
import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.Db;
import dev.waypoint.gateway.support.Fixtures;
import dev.waypoint.gateway.support.MutableClock;
import dev.waypoint.gateway.tenant.Tenant;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class PostgresConfigStoreIT {

    static PostgresConfigStore store;
    static Db db;
    static String url;
    static final MutableClock CLOCK = MutableClock.at("2026-01-01T00:00:00Z");

    @BeforeAll
    static void setUp() {
        url = Containers.newDatabase();
        db = new Db(url);
        new Migrator(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword(), "waypoint_app").migrate();
        new Migrator(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword(), "waypoint_app").migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
        store = new PostgresConfigStore(JdbcClient.create(ds), new DataSourceTransactionManager(ds), new ObjectMapper(), CLOCK);
    }

    @Test
    void writesBumpRevisionAuditAndNotifyInOneTransaction() throws Exception {
        try (Connection listener = db.connect(); Statement s = listener.createStatement()) {
            s.execute("LISTEN " + PostgresConfigStore.CHANNEL);
            long before = store.currentRevision();
            PostgresConfigStore.WriteResult result = store.putRoute(Fixtures.route("n1", "/n1", Map.of("*", List.of())), null, "alice");
            assertThat(result.created()).isTrue();
            assertThat(result.revision()).isEqualTo(before + 1);
            PGNotification[] notifications = listener.unwrap(PGConnection.class).getNotifications(5000);
            assertThat(notifications).isNotEmpty();
            assertThat(notifications[0].getParameter()).isEqualTo("config:" + result.revision());
        }
        assertThat(db.query("SELECT revision FROM config_audit WHERE entity_id = 'n1'").get(0).get("revision"))
                .isEqualTo(store.currentRevision());
    }

    @Test
    void failedWriteChangesNothing() {
        store.putTenant(new Tenant("lock", List.of("/x"), 1, false, false), null, "alice");
        long revision = store.currentRevision();
        assertThatThrownBy(() -> store.putTenant(new Tenant("lock", List.of("/y"), 1, false, false), 99L, "mallory"))
                .isInstanceOf(ConfigConflictException.class);
        assertThat(store.currentRevision()).isEqualTo(revision);
        assertThat(db.query("SELECT count(*) AS n FROM config_audit WHERE actor = 'mallory'").get(0).get("n")).isEqualTo(0L);
    }

    @Test
    void configAuditIsAppendOnly() {
        store.putTenant(new Tenant("aud", List.of("/x"), 1, false, false), null, "alice");
        assertThatThrownBy(() -> db.update("UPDATE config_audit SET actor = 'mallory'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.update("DELETE FROM config_audit")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.update("TRUNCATE config_audit")).hasMessageContaining("append-only");
    }

    @Test
    void seedAppliesOnlyToAnEmptyStore() throws Exception {
        String fresh = Containers.newDatabase();
        new Migrator(fresh, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword(), "waypoint_app").migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(fresh, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
        PostgresConfigStore freshStore = new PostgresConfigStore(JdbcClient.create(ds), new DataSourceTransactionManager(ds), new ObjectMapper(), CLOCK);
        Seeder seeder = new Seeder(freshStore, new RouteValidator(new UpstreamAddressPolicy(Fixtures.noListeners())), new DefaultResourceLoader(),
                true, "classpath:seed-test.yaml");
        assertThat(seeder.seedIfEmpty()).isTrue();
        assertThat(freshStore.currentRevision()).isEqualTo(1);
        assertThat(seeder.seedIfEmpty()).isFalse();
        assertThat(new Seeder(freshStore, null, null, false, "x").seedIfEmpty()).isFalse();
        ConfigSnapshot snapshot = new SnapshotBuilder(new RouteValidator(new UpstreamAddressPolicy(Fixtures.noListeners())), new ObjectMapper())
                .build(freshStore.loadSnapshot());
        assertThat(snapshot.routes().routes()).containsOnlyKeys("weather");
        assertThat(snapshot.tenants()).containsOnlyKeys("acme");
    }

    @Test
    void snapshotBuilderSkipsBadRowsIndividually() {
        String fresh = Containers.newDatabase();
        new Migrator(fresh, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword(), "waypoint_app").migrate();
        Db freshDb = new Db(fresh);
        freshDb.insertRawRoute("good", "/good", "{\"pathPrefix\":\"/good\",\"upstreams\":[{\"url\":\"https://93.184.216.34\"}],\"requiredScopes\":{\"*\":[]}}");
        freshDb.insertRawRoute("bad", "/bad", "{\"pathPrefix\":\"/bad\",\"upstreams\":[{\"url\":\"gopher://x\"}],\"requiredScopes\":{\"*\":[]}}");
        freshDb.insertRawRoute("mismatch", "/m1", "{\"pathPrefix\":\"/m2\",\"upstreams\":[{\"url\":\"https://93.184.216.34\"}],\"requiredScopes\":{\"*\":[]}}");
        freshDb.insertRawRoute("junk", "not a prefix", "{\"nonsense\":true}");
        freshDb.update("INSERT INTO tenants (id, spec, version, updated_at, updated_by) VALUES ('t1', '{\"requestsPerMinute\":-1}', 1, now(), 'x')");
        freshDb.update("INSERT INTO tenants (id, spec, version, updated_at, updated_by) VALUES ('t2', '{\"requestsPerMinute\":5}', 1, now(), 'x')");
        DriverManagerDataSource ds = new DriverManagerDataSource(fresh, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
        PostgresConfigStore freshStore = new PostgresConfigStore(JdbcClient.create(ds), new DataSourceTransactionManager(ds), new ObjectMapper(), CLOCK);
        ConfigSnapshot snapshot = new SnapshotBuilder(new RouteValidator(new UpstreamAddressPolicy(Fixtures.noListeners())), new ObjectMapper())
                .build(freshStore.loadSnapshot());
        assertThat(snapshot.routes().routes()).containsOnlyKeys("good");
        assertThat(snapshot.routes().match("/bad/x")).isInstanceOf(dev.waypoint.gateway.routing.RouteTable.Match.Unavailable.class);
        assertThat(snapshot.routes().match("/m1")).isInstanceOf(dev.waypoint.gateway.routing.RouteTable.Match.Unavailable.class);
        assertThat(snapshot.tenants()).containsOnlyKeys("t2");
        assertThat(snapshot.invalidEntries()).containsExactlyInAnyOrder("route:bad", "route:mismatch", "route:junk", "tenant:t1");
    }

    @Test
    void appRoleHasDmlButCannotRewriteAuditOrChangeSchema() throws Exception {
        String fresh = Containers.newDatabase();
        new Migrator(fresh, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword(), Containers.APP_ROLE).migrate();
        try (Connection app = java.sql.DriverManager.getConnection(fresh, Containers.APP_ROLE, Containers.APP_ROLE_PASSWORD);
             Statement s = app.createStatement()) {
            s.executeUpdate("INSERT INTO tenants (id, spec, version, updated_at, updated_by) VALUES ('t', '{}', 1, now(), 'x')");
            s.executeUpdate("INSERT INTO config_audit (at, actor, action, entity_type, entity_id, revision) VALUES (now(), 'a', 'b', 'c', 'd', 1)");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE config_audit SET actor = 'mallory'")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM config_audit")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("DROP TABLE routes")).hasMessageContaining("must be owner");
        }
    }

    @Test
    void migrationRefusesToRunWithoutTheAppRole() {
        String fresh = Containers.newDatabase();
        Migrator migrator = new Migrator(fresh, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword(), "missing_app_role");
        assertThatThrownBy(migrator::migrate).isInstanceOf(IllegalStateException.class).hasMessageContaining("'missing_app_role' does not exist");
        assertThat(new Db(fresh).query("SELECT to_regclass('flyway_schema_history') IS NULL AS absent").get(0).get("absent"))
                .as("nothing is migrated, so the grants still run once the role exists").isEqualTo(true);
    }

    @Test
    void keyRotationBumpsGenerationAndIsAudited() {
        long generation = store.keyGeneration();
        assertThat(store.rotateKeys("carol")).isEqualTo(generation + 1);
        assertThat(db.query("SELECT actor FROM config_audit WHERE action = 'rotate'")).extracting(r -> r.get("actor")).contains("carol");
        CLOCK.advance(Duration.ofSeconds(1));
    }
}
