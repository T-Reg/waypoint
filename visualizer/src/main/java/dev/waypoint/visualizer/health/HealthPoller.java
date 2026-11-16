package dev.waypoint.visualizer.health;

import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.events.EventHub;
import dev.waypoint.visualizer.health.ServiceStatus.Kind;
import dev.waypoint.visualizer.health.ServiceStatus.State;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Polls every service on the diagram. Gateway instances are identified by the {@code instanceId} their status
 * reports, so one load-balanced management URL (App Platform) can reveal several instances. PostgreSQL and
 * Valkey hold credentials the visualizer never gets: their health is what the gateways report.
 */
@Component
public class HealthPoller {

    static final String LOAD_BALANCER = "lb";
    static final Duration INSTANCE_TTL = Duration.ofSeconds(10);
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(1500);

    private record Seen(String source, JsonNode status, Instant at) {
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final EventHub events;
    private final Clock clock;
    private final Map<String, String> gatewayManagement;
    private final int probes;
    private final String loadBalancerName;
    private final String loadBalancerHealthUrl;
    private final String haproxyStatsUrl;
    private final String upstreamAdminUrl;
    private final Map<String, Seen> instances = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile List<ServiceStatus> latest = List.of();

    public HealthPoller(HttpClient http, ObjectMapper mapper, EventHub events, VisualizerProperties properties, Clock clock) {
        this.http = http;
        this.mapper = mapper;
        this.events = events;
        this.clock = clock;
        this.gatewayManagement = new TreeMap<>(properties.gatewayManagement());
        this.probes = properties.managementProbes();
        this.loadBalancerName = properties.loadBalancerName();
        this.loadBalancerHealthUrl = blankToNull(properties.loadBalancerHealthUrl());
        this.haproxyStatsUrl = blankToNull(properties.haproxyStatsUrl());
        this.upstreamAdminUrl = properties.upstreamAdminUrl();
    }

    public List<ServiceStatus> latest() {
        return latest;
    }

    @Scheduled(fixedDelayString = "${visualizer.health-interval:1500ms}")
    public void pollAndPublish() {
        latest = poll();
        events.publish("health", latest);
    }

    public List<ServiceStatus> poll() {
        CompletableFuture<Map<String, String>> lbView = haproxyStatsUrl == null
                ? CompletableFuture.completedFuture(null) : CompletableFuture.supplyAsync(this::haproxyServers, executor);
        CompletableFuture<Boolean> lbReachable = loadBalancerHealthUrl == null
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> fetch(http, loadBalancerHealthUrl) != null, executor);
        Map<String, List<CompletableFuture<JsonNode>>> statusProbes = new LinkedHashMap<>();
        gatewayManagement.forEach((label, url) -> statusProbes.put(label, IntStream.range(0, probes)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> gatewayStatus(url), executor)).toList()));
        CompletableFuture<Boolean> upstreamUp = CompletableFuture.supplyAsync(
                () -> fetch(http, upstreamAdminUrl + "/health") != null, executor);

        Instant now = clock.instant();
        List<String> unreachable = new ArrayList<>();
        statusProbes.forEach((label, futures) -> {
            List<JsonNode> answers = futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();
            if (answers.isEmpty()) {
                unreachable.add(label);
                instances.values().removeIf(seen -> seen.source().equals(label));
            }
            answers.forEach(status -> instances.put(status.path("instanceId").asString(label), new Seen(label, status, now)));
        });
        instances.values().removeIf(seen -> seen.at().plus(INSTANCE_TTL).isBefore(now));

        Map<String, String> servers = lbView.join();
        List<ServiceStatus> statuses = new ArrayList<>();
        statuses.add(loadBalancer(loadBalancerName, haproxyStatsUrl != null, servers, lbReachable.join()));
        new TreeMap<>(instances).forEach((id, seen) -> statuses.add(gateway(id, seen.status(), lbStatus(servers, id))));
        unreachable.forEach(label -> statuses.add(gateway(label, null, lbStatus(servers, label))));
        statuses.add(new ServiceStatus("upstream", Kind.UPSTREAM, upstreamUp.join() ? State.UP : State.DOWN,
                "WireMock stub upstream", Map.of()));
        List<JsonNode> reports = instances.values().stream().map(Seen::status).toList();
        statuses.add(derived("postgres", Kind.POSTGRES, reports, "configStore", "UP", "config store, as seen by the gateways"));
        statuses.add(derived("valkey", Kind.VALKEY, reports, "rateLimiter", "normal", "shared rate limiter, as seen by the gateways"));
        return statuses;
    }

    private static String lbStatus(Map<String, String> servers, String instance) {
        return servers == null ? null : servers.get(instance);
    }

    private Map<String, String> haproxyServers() {
        String csv = fetch(http, haproxyStatsUrl);
        if (csv == null) {
            return null;
        }
        try {
            return HaproxyStats.serverStatuses(csv);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** With several probes per poll, each uses its own connection so the platform balancer can pick another instance. */
    private JsonNode gatewayStatus(String managementUrl) {
        String body;
        if (probes == 1) {
            body = fetch(http, managementUrl + "/internal/status");
        } else {
            try (HttpClient fresh = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build()) {
                body = fetch(fresh, managementUrl + "/internal/status");
            }
        }
        if (body == null) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    static ServiceStatus loadBalancer(String name, boolean statsConfigured, Map<String, String> servers, Boolean reachable) {
        if (servers != null) {
            boolean allUp = !servers.isEmpty() && servers.values().stream().allMatch("UP"::equals);
            String detail = allUp ? "all backends in rotation" : "some backends out of rotation";
            return new ServiceStatus(LOAD_BALANCER, Kind.LOAD_BALANCER, allUp ? State.UP : State.DEGRADED, detail,
                    Map.of("name", name, "backends", Map.copyOf(servers)));
        }
        if (statsConfigured) {
            return new ServiceStatus(LOAD_BALANCER, Kind.LOAD_BALANCER, State.DOWN, "stats endpoint unreachable",
                    Map.of("name", name));
        }
        if (reachable == null) {
            return new ServiceStatus(LOAD_BALANCER, Kind.LOAD_BALANCER, State.UNKNOWN, "not monitored", Map.of("name", name));
        }
        return new ServiceStatus(LOAD_BALANCER, Kind.LOAD_BALANCER, reachable ? State.UP : State.DOWN,
                reachable ? "reachable" : "unreachable", Map.of("name", name));
    }

    static ServiceStatus gateway(String id, JsonNode status, String lbStatus) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (lbStatus != null) {
            info.put("lb", lbStatus);
        }
        if (status == null) {
            return new ServiceStatus(id, Kind.GATEWAY, State.DOWN, "management listener unreachable", info);
        }
        boolean ready = status.path("ready").asBoolean(false);
        String configStore = status.path("configStore").asString("unknown");
        String rateLimiter = status.path("rateLimiter").asString("unknown");
        JsonNode invalid = status.path("invalidEntries");
        info.put("ready", ready);
        info.put("revision", status.path("revision").asLong(0));
        info.put("kid", status.path("kid").asString(""));
        info.put("configStore", configStore);
        info.put("rateLimiter", rateLimiter);
        info.put("invalidEntries", invalid.isArray() ? invalid.size() : invalid.asInt(0));
        info.put("liveInstances", status.path("liveInstances").asInt(0));
        if (!ready) {
            return new ServiceStatus(id, Kind.GATEWAY, State.DOWN, "not ready (starting or draining)", info);
        }
        if (!"UP".equals(configStore) || !"normal".equals(rateLimiter)) {
            return new ServiceStatus(id, Kind.GATEWAY, State.DEGRADED, "serving, dependency degraded", info);
        }
        return new ServiceStatus(id, Kind.GATEWAY, State.UP, "ready", info);
    }

    static ServiceStatus derived(String id, Kind kind, List<JsonNode> gateways, String field, String healthyValue, String detail) {
        if (gateways.isEmpty()) {
            return new ServiceStatus(id, kind, State.UNKNOWN, "no gateway reachable to ask", Map.of());
        }
        long healthy = gateways.stream().filter(g -> healthyValue.equals(g.path(field).asString(""))).count();
        State state = healthy == gateways.size() ? State.UP : healthy == 0 ? State.DOWN : State.DEGRADED;
        return new ServiceStatus(id, kind, state, detail, Map.of("healthyReports", healthy, "reports", gateways.size()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String fetch(HttpClient client, String url) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(PROBE_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
