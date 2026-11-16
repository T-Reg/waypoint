package dev.waypoint.gateway.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class GatewayBootstrapTest {

    @Test
    void retryMessageNamesTheRootCauseBehindAWrapper() {
        Exception wrapped = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLException("SSL error", new IOException("Empty input")));
        assertThat(GatewayBootstrap.describe(wrapped))
                .isEqualTo("Failed to obtain JDBC Connection (cause: IOException: Empty input)");
    }

    @Test
    void retryMessageIsUnchangedWithoutACause() {
        assertThat(GatewayBootstrap.describe(new IllegalStateException("App role 'x' does not exist")))
                .isEqualTo("App role 'x' does not exist");
    }
}
