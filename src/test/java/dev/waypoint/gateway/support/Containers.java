package dev.waypoint.gateway.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/** One PostgreSQL and one Valkey per test JVM; each test class gets its own database. */
public final class Containers {

    public static final Network NETWORK = Network.newNetwork();

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16.16-alpine"))
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    public static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1.11-alpine"))
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("valkey");

    /** The gateway's DML role, which migrations require to exist (cluster-wide, so created once). */
    public static final String APP_ROLE = "waypoint_app";
    public static final String APP_ROLE_PASSWORD = "app";

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static volatile ToxiproxyContainer toxiproxy;

    static {
        POSTGRES.start();
        VALKEY.start();
        createRole(APP_ROLE, APP_ROLE_PASSWORD);
    }

    private Containers() {}

    /** Creates an empty database and returns its JDBC URL. */
    public static String newDatabase() {
        String name = "gw_" + DATABASES.incrementAndGet() + "_" + ProcessHandle.current().pid();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    public static void createRole(String name, String password) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + name + " LOGIN PASSWORD '" + password + "'");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String valkeyUrl() {
        return "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379);
    }

    public static synchronized ToxiproxyContainer toxiproxy() {
        if (toxiproxy == null) {
            toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")).withNetwork(NETWORK);
            toxiproxy.start();
        }
        return toxiproxy;
    }

    public static void pause(GenericContainer<?> container) {
        DockerClientFactory.instance().client().pauseContainerCmd(container.getContainerId()).exec();
    }

    public static void unpause(GenericContainer<?> container) {
        DockerClientFactory.instance().client().unpauseContainerCmd(container.getContainerId()).exec();
    }

    public static void flushValkey() {
        try {
            VALKEY.execInContainer("valkey-cli", "FLUSHALL");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
