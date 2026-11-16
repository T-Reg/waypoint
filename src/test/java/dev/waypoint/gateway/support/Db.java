package dev.waypoint.gateway.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Direct SQL against a test database, for tampering scenarios the admin API would refuse. */
public final class Db {

    private final String url;

    public Db(String url) {
        this.url = url;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, Containers.POSTGRES.getUsername(), Containers.POSTGRES.getPassword());
    }

    public int update(String sql, Object... params) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setObject(i + 1, params[i]);
            }
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... params) {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setObject(i + 1, params[i]);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inserts a route row verbatim (bypassing validation), bumps the revision and notifies. */
    public void insertRawRoute(String id, String prefix, String specJson) {
        update("INSERT INTO routes (id, path_prefix, spec, version, updated_at, updated_by) VALUES (?, ?, CAST(? AS jsonb), 1, now(), 'test')",
                id, prefix, specJson);
        bumpRevision();
    }

    public void bumpRevision() {
        update("UPDATE config_revision SET revision = revision + 1 WHERE id = 1");
        query("SELECT pg_notify('gateway_events', 'config:test')");
    }
}
