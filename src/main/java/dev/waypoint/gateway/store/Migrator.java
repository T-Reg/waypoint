package dev.waypoint.gateway.store;

import java.util.Map;
import org.flywaydb.core.Flyway;

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
        Flyway.configure()
                .dataSource(url, username, password)
                .jdbcProperties(Map.of("connectTimeout", "5", "socketTimeout", "30"))
                .locations("classpath:db/migration")
                .placeholders(Map.of("appRole", appRole))
                .load()
                .migrate();
    }
}
