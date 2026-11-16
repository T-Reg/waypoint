package dev.waypoint.gateway.support;

import static org.awaitility.Awaitility.await;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.waypoint.gateway.GatewayApplication;
import dev.waypoint.gateway.listener.ListenerPorts;
import dev.waypoint.gateway.store.ConfigSync;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** One gateway process-equivalent: a full Spring context on random ports, driven over real HTTP. */
public final class TestGateway implements AutoCloseable {

    public static final String HMAC_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final ConfigurableApplicationContext context;

    private TestGateway(String name, ConfigurableApplicationContext context) {
        this.name = name;
        this.context = context;
    }

    public static Builder builder(String databaseUrl) {
        return new Builder(databaseUrl);
    }

    public static final class Builder {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private String name = "gw";

        Builder(String databaseUrl) {
            properties.put("server.port", 0);
            properties.put("gateway.listeners.admin-port", 0);
            properties.put("gateway.listeners.management-port", 0);
            properties.put("spring.datasource.url", databaseUrl);
            properties.put("spring.datasource.username", Containers.POSTGRES.getUsername());
            properties.put("spring.datasource.password", Containers.POSTGRES.getPassword());
            properties.put("spring.data.redis.url", Containers.valkeyUrl());
            properties.put("gateway.auth.customer.public-jwk", TestTokens.ISSUER_KEY.toPublicJWK().toJSONString());
            properties.put("gateway.auth.customer.public-jwk-file", "");
            properties.put("gateway.auth.admin.public-jwk", TestTokens.ADMIN_KEY.toPublicJWK().toJSONString());
            properties.put("gateway.auth.admin.public-jwk-file", "");
            properties.put("gateway.audit.path-hmac-key", HMAC_KEY);
            properties.put("gateway.identity.token-ttl", "60s");
            properties.put("gateway.identity.heartbeat-interval", "300ms");
            properties.put("gateway.identity.key-stale-after", "10m");
            properties.put("gateway.store.poll-interval", "500ms");
            properties.put("gateway.store.startup-retry-max", "500ms");
            properties.put("gateway.lifecycle.shutdown-delay", "0s");
            properties.put("spring.lifecycle.timeout-per-shutdown-phase", "20s");
            properties.put("spring.main.register-shutdown-hook", false);
        }

        public Builder name(String name) {
            this.name = name;
            properties.put("gateway.instance-id", name);
            return this;
        }

        public Builder property(String key, Object value) {
            properties.put(key, value);
            return this;
        }

        /** Starts the context without waiting for readiness. */
        public TestGateway start() {
            String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
            ConfigurableApplicationContext context = new SpringApplicationBuilder(GatewayApplication.class)
                    .registerShutdownHook(false)
                    .run(args);
            return new TestGateway(name, context);
        }

        public TestGateway startReady() {
            TestGateway instance = start();
            instance.awaitReady();
            return instance;
        }

        /** Starts in the background, for startups expected to block (e.g. on a paused DB). */
        public CompletableFuture<TestGateway> startAsync() {
            return CompletableFuture.supplyAsync(this::start);
        }
    }

    public String name() {
        return name;
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public int publicPort() {
        return bean(ListenerPorts.class).publicPort();
    }

    public int adminPort() {
        return bean(ListenerPorts.class).adminPort();
    }

    public int managementPort() {
        return bean(ListenerPorts.class).managementPort();
    }

    public void awaitReady() {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(this::isReady);
    }

    public boolean isReady() {
        try {
            return send(managementPort(), "GET", "/actuator/health/readiness", null, null, Map.of()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public JsonNode status() {
        return json(send(managementPort(), "GET", "/internal/status", null, null, Map.of()));
    }

    public long revision() {
        return status().path("revision").asLong();
    }

    public JsonNode jwks() {
        return json(send(managementPort(), "GET", "/.well-known/jwks.json", null, null, Map.of()));
    }

    public String jwksJson() {
        return send(managementPort(), "GET", "/.well-known/jwks.json", null, null, Map.of()).body();
    }

    public HttpResponse<String> get(String path, String token) {
        return send(publicPort(), "GET", path, token, null, Map.of());
    }

    public HttpResponse<String> call(String method, String path, String token, String body, Map<String, String> headers) {
        return send(publicPort(), method, path, token, body, headers);
    }

    public HttpResponse<String> admin(String method, String path, String token, String body, Map<String, String> headers) {
        return send(adminPort(), method, path, token, body, headers);
    }

    public HttpResponse<String> putRoute(String id, String json) {
        return putRoute(id, json, null);
    }

    public HttpResponse<String> putRoute(String id, String json, String ifMatch) {
        Map<String, String> headers = ifMatch == null ? Map.of() : Map.of("If-Match", ifMatch);
        return admin("PUT", "/admin/routes/" + id, TestTokens.admin("alice", "admin:routes"), json, headers);
    }

    public HttpResponse<String> putTenant(String id, String json) {
        return putTenant(id, json, null);
    }

    public HttpResponse<String> putTenant(String id, String json, String ifMatch) {
        Map<String, String> headers = ifMatch == null ? Map.of() : Map.of("If-Match", ifMatch);
        return admin("PUT", "/admin/tenants/" + id, TestTokens.admin("alice", "admin:tenants"), json, headers);
    }

    /** Creates or updates, fetching the current ETag first when the entry exists. */
    public void upsertRoute(String id, String json) {
        HttpResponse<String> current = admin("GET", "/admin/routes/" + id, TestTokens.admin("alice", "admin:routes"), null, Map.of());
        HttpResponse<String> response = putRoute(id, json, current.statusCode() == 200 ? current.headers().firstValue("ETag").orElseThrow() : null);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("route upsert failed: " + response.statusCode() + " " + response.body());
        }
    }

    public void upsertTenant(String id, String json) {
        HttpResponse<String> current = admin("GET", "/admin/tenants/" + id, TestTokens.admin("alice", "admin:tenants"), null, Map.of());
        HttpResponse<String> response = putTenant(id, json, current.statusCode() == 200 ? current.headers().firstValue("ETag").orElseThrow() : null);
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("tenant upsert failed: " + response.statusCode() + " " + response.body());
        }
    }

    public HttpResponse<String> rotateKeys() {
        return admin("POST", "/admin/keys/rotate", TestTokens.admin("alice", "admin:keys"), null, Map.of());
    }

    /** Simulates a crash: stops serving and heartbeating without any graceful-shutdown work. */
    public void crash() {
        bean(ConfigSync.class).stop();
        ((ServletWebServerApplicationContext) context).getWebServer().stop();
    }

    @Override
    public void close() {
        if (context.isActive()) {
            context.close();
        }
    }

    public static HttpResponse<String> send(int port, String method, String path, String token, String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOExceptionWithStatus(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode json(HttpResponse<String> response) {
        try {
            return MAPPER.readTree(response.body());
        } catch (JacksonException e) {
            throw new IllegalStateException("not JSON (" + response.statusCode() + "): " + response.body(), e);
        }
    }

    public static final class UncheckedIOExceptionWithStatus extends RuntimeException {
        UncheckedIOExceptionWithStatus(IOException cause) {
            super(cause);
        }
    }
}
