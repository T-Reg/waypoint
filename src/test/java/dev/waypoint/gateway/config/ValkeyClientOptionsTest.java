package dev.waypoint.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ValkeyClientOptionsTest {

    private static SocketOptions customized(Duration connectTimeout) {
        ClientOptions.Builder builder = ClientOptions.builder();
        ValkeyClientOptions.tcpKeepAlive(connectTimeout).customize(builder);
        return builder.build().getSocketOptions();
    }

    @Test
    void enablesTcpKeepAliveAndKeepsTheConfiguredConnectTimeout() {
        SocketOptions socket = customized(Duration.ofMillis(200));
        assertThat(socket.getConnectTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(socket.isKeepAlive()).isTrue();
        assertThat(socket.getKeepAlive().getIdle()).isEqualTo(ValkeyClientOptions.KEEPALIVE_IDLE);
        assertThat(socket.getKeepAlive().getInterval()).isEqualTo(ValkeyClientOptions.KEEPALIVE_INTERVAL);
        assertThat(socket.getKeepAlive().getCount()).isEqualTo(ValkeyClientOptions.KEEPALIVE_COUNT);
    }

    @Test
    void fallsBackToLettucesDefaultConnectTimeoutWhenUnset() {
        assertThat(customized(null).getConnectTimeout()).isEqualTo(SocketOptions.DEFAULT_CONNECT_TIMEOUT_DURATION);
    }
}
