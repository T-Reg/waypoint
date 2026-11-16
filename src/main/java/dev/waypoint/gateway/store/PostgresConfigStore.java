package dev.waypoint.gateway.store;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.tenant.Tenant;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Source of truth for routes and tenants. Every write bumps {@code config_revision}, appends to
 * {@code config_audit} and sends {@code NOTIFY} in the same transaction. The request path never calls this.
 */
public class PostgresConfigStore {

    public static final String CHANNEL = "gateway_events";

    public enum Table {
        ROUTES("routes", "route"), TENANTS("tenants", "tenant");

        final String table;
        final String entityType;

        Table(String table, String entityType) {
            this.table = table;
            this.entityType = entityType;
        }
    }

    public record Row(String id, String pathPrefix, String specJson, long version, Instant updatedAt, String updatedBy) {}

    public record RawSnapshot(long revision, List<Row> routes, List<Row> tenants) {}

    public record WriteResult(long version, long revision, boolean created) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate snapshotTx;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PostgresConfigStore(JdbcClient jdbc, PlatformTransactionManager txManager, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.snapshotTx = new TransactionTemplate(txManager);
        snapshotTx.setReadOnly(true);
        snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public RawSnapshot loadSnapshot() {
        return snapshotTx.execute(status -> new RawSnapshot(
                currentRevision(),
                jdbc.sql("SELECT id, path_prefix, spec::text AS spec, version, updated_at, updated_by FROM routes")
                        .query((rs, i) -> row(rs, true)).list(),
                jdbc.sql("SELECT id, spec::text AS spec, version, updated_at, updated_by FROM tenants")
                        .query((rs, i) -> row(rs, false)).list()));
    }

    public long currentRevision() {
        return jdbc.sql("SELECT revision FROM config_revision WHERE id = 1").query(Long.class).single();
    }

    public long keyGeneration() {
        return jdbc.sql("SELECT generation FROM key_rotation WHERE id = 1").query(Long.class).single();
    }

    public Optional<Row> get(Table table, String id) {
        return jdbc.sql(selectSql(table) + " WHERE id = :id").param("id", id)
                .query((rs, i) -> row(rs, table == Table.ROUTES)).optional();
    }

    public List<Row> list(Table table) {
        return jdbc.sql(selectSql(table) + " ORDER BY id").query((rs, i) -> row(rs, table == Table.ROUTES)).list();
    }

    public WriteResult putRoute(Route route, Long expectedVersion, String actor) {
        return put(Table.ROUTES, route.id(), route.pathPrefix(), toJson(route.toSpec()), expectedVersion, actor);
    }

    public WriteResult putTenant(Tenant tenant, Long expectedVersion, String actor) {
        return put(Table.TENANTS, tenant.id(), null, toJson(tenant.toSpec()), expectedVersion, actor);
    }

    public long delete(Table table, String id, Long expectedVersion, String actor) {
        return writeTx.execute(status -> {
            Row existing = lockRow(table, id).orElseThrow(() -> new ConfigNotFoundException(table.entityType + " not found"));
            if (expectedVersion != null && existing.version() != expectedVersion) {
                throw new ConfigConflictException("version mismatch: current version is " + existing.version());
            }
            jdbc.sql("DELETE FROM " + table.table + " WHERE id = :id").param("id", id).update();
            long revision = bumpRevision();
            audit(actor, "delete", table.entityType, id, existing.specJson(), null, revision);
            notifyChange("config:" + revision);
            return revision;
        });
    }

    /** Bumps the key generation; every instance rotates when it sees the new value. */
    public long rotateKeys(String actor) {
        return writeTx.execute(status -> {
            long generation = jdbc.sql("UPDATE key_rotation SET generation = generation + 1 WHERE id = 1 RETURNING generation")
                    .query(Long.class).single();
            long revision = currentRevision();
            audit(actor, "rotate", "gateway_keys", "generation-" + generation, null, null, revision);
            notifyChange("keys:" + generation);
            return generation;
        });
    }

    /** Applies the seed only to an empty store, so runtime changes are never overwritten. */
    public boolean seedIfEmpty(List<Route> routes, List<Tenant> tenants, String actor) {
        return Boolean.TRUE.equals(writeTx.execute(status -> {
            long revision = jdbc.sql("SELECT revision FROM config_revision WHERE id = 1 FOR UPDATE").query(Long.class).single();
            if (revision != 0) {
                return false;
            }
            long next = bumpRevision();
            Instant now = clock.instant();
            for (Route route : routes) {
                String json = toJson(route.toSpec());
                insert(Table.ROUTES, route.id(), route.pathPrefix(), json, now, actor);
                audit(actor, "seed", Table.ROUTES.entityType, route.id(), null, json, next);
            }
            for (Tenant tenant : tenants) {
                String json = toJson(tenant.toSpec());
                insert(Table.TENANTS, tenant.id(), null, json, now, actor);
                audit(actor, "seed", Table.TENANTS.entityType, tenant.id(), null, json, next);
            }
            notifyChange("config:" + next);
            return true;
        }));
    }

    public void notifyChange(String payload) {
        jdbc.sql("SELECT pg_notify('" + CHANNEL + "', :payload)").param("payload", payload).query().listOfRows();
    }

    private WriteResult put(Table table, String id, String pathPrefix, String json, Long expectedVersion, String actor) {
        try {
            return writeTx.execute(status -> {
                Optional<Row> existing = lockRow(table, id);
                Instant now = clock.instant();
                long version;
                if (existing.isPresent()) {
                    if (expectedVersion == null) {
                        throw new PreconditionRequiredException();
                    }
                    if (existing.get().version() != expectedVersion) {
                        throw new ConfigConflictException("version mismatch: current version is " + existing.get().version());
                    }
                    version = expectedVersion + 1;
                    jdbc.sql("UPDATE " + table.table + " SET spec = CAST(:spec AS jsonb), version = :version, updated_at = :at, updated_by = :actor"
                                    + (pathPrefix != null ? ", path_prefix = :prefix" : "") + " WHERE id = :id")
                            .param("spec", json).param("version", version).param("at", timestamp(now)).param("actor", actor)
                            .param("prefix", pathPrefix).param("id", id).update();
                } else {
                    if (expectedVersion != null) {
                        throw new ConfigConflictException(table.entityType + " does not exist");
                    }
                    version = 1;
                    insert(table, id, pathPrefix, json, now, actor);
                }
                long revision = bumpRevision();
                audit(actor, existing.isPresent() ? "update" : "create", table.entityType, id,
                        existing.map(Row::specJson).orElse(null), json, revision);
                notifyChange("config:" + revision);
                return new WriteResult(version, revision, existing.isEmpty());
            });
        } catch (DuplicateKeyException e) {
            throw new ConfigConflictException(table == Table.ROUTES
                    ? "a route with this id or pathPrefix already exists" : "tenant already exists");
        }
    }

    private void insert(Table table, String id, String pathPrefix, String json, Instant now, String actor) {
        if (table == Table.ROUTES) {
            jdbc.sql("INSERT INTO routes (id, path_prefix, spec, version, updated_at, updated_by) VALUES (:id, :prefix, CAST(:spec AS jsonb), 1, :at, :actor)")
                    .param("id", id).param("prefix", pathPrefix).param("spec", json).param("at", timestamp(now)).param("actor", actor).update();
        } else {
            jdbc.sql("INSERT INTO tenants (id, spec, version, updated_at, updated_by) VALUES (:id, CAST(:spec AS jsonb), 1, :at, :actor)")
                    .param("id", id).param("spec", json).param("at", timestamp(now)).param("actor", actor).update();
        }
    }

    private Optional<Row> lockRow(Table table, String id) {
        return jdbc.sql(selectSql(table) + " WHERE id = :id FOR UPDATE").param("id", id)
                .query((rs, i) -> row(rs, table == Table.ROUTES)).optional();
    }

    private long bumpRevision() {
        return jdbc.sql("UPDATE config_revision SET revision = revision + 1 WHERE id = 1 RETURNING revision").query(Long.class).single();
    }

    private void audit(String actor, String action, String entityType, String entityId, String before, String after, long revision) {
        jdbc.sql("INSERT INTO config_audit (at, actor, action, entity_type, entity_id, before, after, revision) "
                        + "VALUES (:at, :actor, :action, :type, :entityId, CAST(:before AS jsonb), CAST(:after AS jsonb), :revision)")
                .param("at", timestamp(clock.instant())).param("actor", actor).param("action", action).param("type", entityType)
                .param("entityId", entityId).param("before", before).param("after", after).param("revision", revision).update();
    }

    private static String selectSql(Table table) {
        return table == Table.ROUTES
                ? "SELECT id, path_prefix, spec::text AS spec, version, updated_at, updated_by FROM routes"
                : "SELECT id, spec::text AS spec, version, updated_at, updated_by FROM tenants";
    }

    private static Row row(java.sql.ResultSet rs, boolean withPrefix) throws java.sql.SQLException {
        return new Row(rs.getString("id"), withPrefix ? rs.getString("path_prefix") : null, rs.getString("spec"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("updated_by"));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot serialize config entry", e);
        }
    }
}
