package dev.waypoint.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gateway")
public record GatewayProperties(
        String instanceId,
        @DefaultValue Listeners listeners,
        @DefaultValue Auth auth,
        @DefaultValue Identity identity,
        @DefaultValue Proxy proxy,
        @DefaultValue Audit audit,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Store store,
        @DefaultValue Seed seed,
        @DefaultValue Lifecycle lifecycle) {

    public record Listeners(
            @DefaultValue("8081") int adminPort,
            @DefaultValue("8082") int managementPort,
            String adminAddress,
            String managementAddress) {}

    /** Trust settings for one inbound token type. {@code publicJwk} is an inline JWK or JWK set. */
    public record TokenTrust(String issuer, String audience, String publicJwk, String publicJwkFile) {}

    public record Auth(
            @DefaultValue TokenTrust customer,
            @DefaultValue TokenTrust admin,
            @DefaultValue("30s") Duration clockSkew) {}

    public record Identity(
            @DefaultValue("waypoint-gateway") String issuer,
            @DefaultValue("60s") Duration tokenTtl,
            @DefaultValue("10s") Duration heartbeatInterval,
            @DefaultValue("10m") Duration keyStaleAfter,
            @DefaultValue("10s") Duration keyPublishGrace) {}

    /** {@code exposeInstanceHeader} adds {@code X-Gateway-Instance} to public responses; off by default (it names the instance). */
    public record Proxy(
            @DefaultValue("10485760") long maxRequestBytes,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue List<String> trustedProxies,
            @DefaultValue("false") boolean exposeInstanceHeader) {}

    public record Audit(String pathHmacKey) {}

    public record RateLimit(
            @DefaultValue("valkey") Mode mode,
            @DefaultValue("1s") Duration probeInterval,
            @DefaultValue("10s") Duration keepAliveInterval) {

        public enum Mode { VALKEY, LOCAL }
    }

    public record Store(
            @DefaultValue("10s") Duration pollInterval,
            @DefaultValue("5s") Duration startupRetryMax,
            @DefaultValue Connection listen,
            @DefaultValue Connection migration,
            @DefaultValue("waypoint_app") String appRole) {}

    /** Optional override connection; any blank field falls back to {@code spring.datasource.*}. */
    public record Connection(String url, String username, String password) {}

    public record Seed(@DefaultValue("false") boolean ifEmpty, String file) {}

    public record Lifecycle(@DefaultValue("10s") Duration shutdownDelay) {}
}
