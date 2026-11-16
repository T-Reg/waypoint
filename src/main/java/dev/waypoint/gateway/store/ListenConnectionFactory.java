package dev.waypoint.gateway.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Dedicated direct connection for LISTEN; it can't go through a transaction-mode pooler such as PgBouncer. */
public class ListenConnectionFactory {

    private final String url;
    private final String username;
    private final String password;
    private final String applicationName;

    public ListenConnectionFactory(String url, String username, String password, String applicationName) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.applicationName = applicationName;
    }

    public Connection open() throws SQLException {
        Properties props = new Properties();
        if (username != null) {
            props.setProperty("user", username);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        props.setProperty("ApplicationName", applicationName);
        props.setProperty("connectTimeout", "5");
        props.setProperty("socketTimeout", "10");
        props.setProperty("tcpKeepAlive", "true");
        return DriverManager.getConnection(url, props);
    }

    public String applicationName() {
        return applicationName;
    }
}
