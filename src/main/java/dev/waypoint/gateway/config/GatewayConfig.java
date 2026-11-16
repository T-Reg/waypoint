package dev.waypoint.gateway.config;

import tools.jackson.databind.ObjectMapper;
import dev.waypoint.gateway.admin.AdminGate;
import dev.waypoint.gateway.audit.AuditRecorder;
import dev.waypoint.gateway.audit.AuditSink;
import dev.waypoint.gateway.audit.JsonAuditSink;
import dev.waypoint.gateway.audit.PathHasher;
import dev.waypoint.gateway.auth.AdminAuthenticator;
import dev.waypoint.gateway.auth.CustomerAuthenticator;
import dev.waypoint.gateway.error.ProblemWriter;
import dev.waypoint.gateway.identity.GatewayKeyStore;
import dev.waypoint.gateway.identity.IdentityTokenMinter;
import dev.waypoint.gateway.identity.KeyRegistry;
import dev.waypoint.gateway.identity.KeyService;
import dev.waypoint.gateway.listener.ListenerFilter;
import dev.waypoint.gateway.listener.ListenerPorts;
import dev.waypoint.gateway.management.GatewayHealthIndicator;
import dev.waypoint.gateway.management.GatewayInstance;
import dev.waypoint.gateway.management.ShutdownDelay;
import dev.waypoint.gateway.pipeline.GatewayPipeline;
import dev.waypoint.gateway.pipeline.Stages;
import dev.waypoint.gateway.proxy.ClientIpResolver;
import dev.waypoint.gateway.proxy.JdkUpstreamClient;
import dev.waypoint.gateway.proxy.ProxyHandler;
import dev.waypoint.gateway.proxy.UpstreamClient;
import dev.waypoint.gateway.ratelimit.LocalRateLimiter;
import dev.waypoint.gateway.ratelimit.RateLimiter;
import dev.waypoint.gateway.ratelimit.ResilientRateLimiter;
import dev.waypoint.gateway.ratelimit.ValkeyRateLimiter;
import dev.waypoint.gateway.routing.RouteValidator;
import dev.waypoint.gateway.routing.UpstreamAddressPolicy;
import dev.waypoint.gateway.routing.UpstreamSelector;
import dev.waypoint.gateway.store.ConfigSync;
import dev.waypoint.gateway.store.GatewayBootstrap;
import dev.waypoint.gateway.store.GatewayState;
import dev.waypoint.gateway.store.ListenConnectionFactory;
import dev.waypoint.gateway.store.Migrator;
import dev.waypoint.gateway.store.PostgresConfigStore;
import dev.waypoint.gateway.store.Seeder;
import dev.waypoint.gateway.store.SnapshotBuilder;
import dev.waypoint.gateway.store.SnapshotHolder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HexFormat;
import java.security.SecureRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    GatewayInstance gatewayInstance(GatewayProperties properties) {
        String id = properties.instanceId();
        if (id == null || id.isBlank()) {
            byte[] random = new byte[4];
            new SecureRandom().nextBytes(random);
            id = "gw-" + HexFormat.of().formatHex(random);
        }
        return new GatewayInstance(id);
    }

    @Bean
    GatewayState gatewayState() {
        return new GatewayState();
    }

    @Bean
    SnapshotHolder snapshotHolder(MeterRegistry meters) {
        SnapshotHolder holder = new SnapshotHolder();
        Gauge.builder("config.invalid_entries", holder, h -> h.get().invalidEntries().size()).register(meters);
        Gauge.builder("config.revision", holder, h -> h.get().revision()).register(meters);
        return holder;
    }

    @Bean
    UpstreamAddressPolicy upstreamAddressPolicy(ListenerPorts ports) {
        return new UpstreamAddressPolicy(ports);
    }

    @Bean
    RouteValidator routeValidator(UpstreamAddressPolicy policy) {
        return new RouteValidator(policy);
    }

    @Bean
    PostgresConfigStore postgresConfigStore(JdbcClient jdbc, PlatformTransactionManager tx, ObjectMapper mapper, Clock clock) {
        return new PostgresConfigStore(jdbc, tx, mapper, clock);
    }

    @Bean
    SnapshotBuilder snapshotBuilder(RouteValidator validator, ObjectMapper mapper) {
        return new SnapshotBuilder(validator, mapper);
    }

    @Bean
    GatewayKeyStore gatewayKeyStore(GatewayProperties properties, Clock clock) {
        return new GatewayKeyStore(clock, properties.identity().tokenTtl().plus(properties.auth().clockSkew()));
    }

    @Bean
    KeyRegistry keyRegistry(JdbcClient jdbc) {
        return new KeyRegistry(jdbc);
    }

    @Bean
    KeyService keyService(GatewayKeyStore keyStore, KeyRegistry registry, Clock clock, GatewayInstance instance, GatewayProperties properties) {
        GatewayProperties.Identity identity = properties.identity();
        return new KeyService(keyStore, registry, clock, instance.id(), identity.tokenTtl(), properties.auth().clockSkew(),
                identity.heartbeatInterval(), identity.keyStaleAfter());
    }

    @Bean
    IdentityTokenMinter identityTokenMinter(GatewayKeyStore keyStore, GatewayProperties properties, Clock clock) {
        return new IdentityTokenMinter(keyStore, properties.identity().issuer(), properties.identity().tokenTtl(), clock);
    }

    @Bean
    ListenConnectionFactory listenConnectionFactory(DataSourceProperties dataSource, GatewayProperties properties, GatewayInstance instance) {
        GatewayProperties.Connection listen = properties.store().listen();
        return new ListenConnectionFactory(
                orDefault(listen.url(), dataSource.determineUrl()),
                orDefault(listen.username(), dataSource.determineUsername()),
                orDefault(listen.password(), dataSource.determinePassword()),
                "waypoint-listen-" + instance.id());
    }

    @Bean
    ConfigSync configSync(PostgresConfigStore store, SnapshotBuilder builder, SnapshotHolder holder, KeyService keys,
                          GatewayState state, ListenConnectionFactory listen, GatewayProperties properties) {
        return new ConfigSync(store, builder, holder, keys, state, listen, properties.store().pollInterval(),
                properties.identity().heartbeatInterval(), properties.store().startupRetryMax());
    }

    @Bean
    Seeder seeder(PostgresConfigStore store, RouteValidator validator, ResourceLoader resources, GatewayProperties properties) {
        return new Seeder(store, validator, resources, properties.seed().ifEmpty(), properties.seed().file());
    }

    @Bean
    Migrator migrator(DataSourceProperties dataSource, GatewayProperties properties) {
        GatewayProperties.Connection migration = properties.store().migration();
        return new Migrator(
                orDefault(migration.url(), dataSource.determineUrl()),
                orDefault(migration.username(), dataSource.determineUsername()),
                orDefault(migration.password(), dataSource.determinePassword()),
                properties.store().appRole());
    }

    @Bean
    GatewayBootstrap gatewayBootstrap(Migrator migrator, Seeder seeder, PostgresConfigStore store, SnapshotBuilder builder,
                                      SnapshotHolder holder, KeyService keys, ConfigSync sync, GatewayState state,
                                      GatewayProperties properties) {
        return new GatewayBootstrap(migrator, seeder, store, builder, holder, keys, sync, state, properties.store().startupRetryMax());
    }

    @Bean
    RateLimiter rateLimiter(GatewayProperties properties, ObjectProvider<StringRedisTemplate> redis, KeyService keys,
                            Clock clock, MeterRegistry meters) {
        LocalRateLimiter local = new LocalRateLimiter(clock);
        if (properties.rateLimit().mode() == GatewayProperties.RateLimit.Mode.LOCAL) {
            return local;
        }
        ResilientRateLimiter limiter = new ResilientRateLimiter(new ValkeyRateLimiter(redis.getObject()), local,
                keys::liveInstances, clock, properties.rateLimit().probeInterval());
        Gauge.builder("ratelimit.degraded", limiter, l -> l.isDegraded() ? 1 : 0).register(meters);
        return limiter;
    }

    @Bean
    ProblemWriter problemWriter(ObjectMapper mapper) {
        return new ProblemWriter(mapper);
    }

    @Bean
    PathHasher pathHasher(GatewayProperties properties) {
        return PathHasher.fromBase64(properties.audit().pathHmacKey());
    }

    @Bean
    AuditSink auditSink(ObjectMapper mapper) {
        return new JsonAuditSink(mapper);
    }

    @Bean
    AuditRecorder auditRecorder(AuditSink sink, PathHasher hasher, Clock clock) {
        return new AuditRecorder(sink, hasher, clock);
    }

    @Bean
    ClientIpResolver clientIpResolver(GatewayProperties properties) {
        return new ClientIpResolver(properties.proxy().trustedProxies());
    }

    @Bean
    UpstreamClient upstreamClient(GatewayProperties properties) {
        return new JdkUpstreamClient(properties.proxy().connectTimeout());
    }

    @Bean
    UpstreamSelector upstreamSelector() {
        return UpstreamSelector.primary();
    }

    @Bean
    ProxyHandler proxyHandler(UpstreamClient client, UpstreamSelector selector, UpstreamAddressPolicy policy,
                              IdentityTokenMinter minter, ProblemWriter problems, GatewayProperties properties) {
        return new ProxyHandler(client, selector, policy, minter, problems, properties.proxy().maxRequestBytes());
    }

    @Bean
    GatewayPipeline gatewayPipeline(CustomerAuthenticator authenticator, RateLimiter rateLimiter, ProxyHandler proxy,
                                    SnapshotHolder snapshots, GatewayState state, ClientIpResolver clientIps,
                                    ProblemWriter problems, AuditRecorder audit, GatewayProperties properties) {
        return new GatewayPipeline(Stages.ordered(properties.proxy().maxRequestBytes(), authenticator, rateLimiter),
                proxy, snapshots, state, clientIps, problems, audit);
    }

    @Bean
    AdminGate adminGate(AdminAuthenticator authenticator, ProblemWriter problems, AuditRecorder audit) {
        return new AdminGate(authenticator, problems, audit);
    }

    @Bean
    ListenerFilter listenerFilter(ListenerPorts ports, GatewayPipeline pipeline, AdminGate adminGate, ProblemWriter problems) {
        return new ListenerFilter(ports, pipeline, adminGate, problems);
    }

    @Bean("gateway")
    GatewayHealthIndicator gatewayHealthIndicator(GatewayState state, KeyService keys) {
        return new GatewayHealthIndicator(state, keys);
    }

    @Bean
    ShutdownDelay shutdownDelay(GatewayState state, GatewayProperties properties) {
        return new ShutdownDelay(state, properties.lifecycle().shutdownDelay());
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
