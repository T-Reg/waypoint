package dev.waypoint.gateway.config;

import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;

/** TCP keepalive on the Valkey connection, so the OS detects a silently dropped connection between pings. */
final class ValkeyClientOptions {

    static final Duration KEEPALIVE_IDLE = Duration.ofSeconds(30);
    static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(10);
    static final int KEEPALIVE_COUNT = 3;

    private ValkeyClientOptions() {}

    /** Replaces Boot's socket options, so it carries over {@code spring.data.redis.connect-timeout}. */
    static LettuceClientOptionsBuilderCustomizer tcpKeepAlive(Duration connectTimeout) {
        Duration timeout = connectTimeout != null ? connectTimeout : SocketOptions.DEFAULT_CONNECT_TIMEOUT_DURATION;
        SocketOptions socket = SocketOptions.builder()
                .connectTimeout(timeout)
                .keepAlive(SocketOptions.KeepAliveOptions.builder()
                        .enable()
                        .idle(KEEPALIVE_IDLE)
                        .interval(KEEPALIVE_INTERVAL)
                        .count(KEEPALIVE_COUNT)
                        .build())
                .build();
        return options -> options.socketOptions(socket);
    }
}
