package dev.waypoint.gateway.store;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Runs Flyway with the migration (DDL) credential; Flyway's lock makes concurrent starts safe. */
public class Migrator {

    private final String url;
    private final String username;
    private final String password;
    private final String appRole;

    public Migrator(String url, String username, String password, String appRole) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.appRole = appRole;
    }

    public void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .jdbcProperties(Map.of("connectTimeout", "5", "socketTimeout", "30"))
                .locations("classpath:db/migration")
                .placeholders(Map.of("appRole", appRole))
                .load();
        requireAppRole(flyway.getConfiguration().getDataSource());
        flyway.migrate();
    }

    // V2 grants run once and skip a missing role, so a role created later would never get access.
    private void requireAppRole(DataSource dataSource) {
        boolean exists = JdbcClient.create(dataSource)
                .sql("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)")
                .param(appRole)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new IllegalStateException("App role '" + appRole + "' does not exist; create it before starting the gateway");
        }
    }
}
