package dev.waypoint.visualizer.health;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.waypoint.visualizer.config.VisualizerConfig;
import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.events.EventHub;
import dev.waypoint.visualizer.health.ServiceStatus.Kind;
import dev.waypoint.visualizer.health.ServiceStatus.State;
import dev.waypoint.visualizer.support.MutableClock;
import dev.waypoint.visualizer.support.TestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HealthPollerIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final MutableClock clock = new MutableClock(TestSupport.NOW);
    private WireMockServer stack;

    @BeforeEach
    void start() {
        stack = new WireMockServer(options().dynamicPort());
        stack.start();
    }

    @AfterEach
    void stop() {
        stack.stop();
    }

    private static String status(String instanceId) {
        return """
                {"instanceId":"%s","ready":true,"revision":7,"invalidEntries":[],"keyGeneration":1,"kid":"k1",
                 "configStore":"UP","listenConnected":true,"liveInstances":2,"rateLimiter":"normal"}""".formatted(instanceId);
    }

    /** Compose-style: one management URL per instance, HAProxy stats for the load balancer. */
    private HealthPoller composePoller(String statsPath) {
        VisualizerProperties properties = TestSupport.properties(stack.baseUrl(), stack.baseUrl() + "/__upstream",
                stack.baseUrl() + "/jwks",
                Map.of("gateway-1", stack.baseUrl() + "/g1", "gateway-2", "http://127.0.0.1:1"),
                stack.baseUrl() + statsPath, new VisualizerProperties.Access(false, "demo", ""));
        return new HealthPoller(new VisualizerConfig().httpClient(), MAPPER, new EventHub(), properties, clock);
    }

    /** App Platform-style: one load-balanced management URL probed several times, and a load balancer health URL. */
    private HealthPoller platformPoller(String lbHealthPath) {
        VisualizerProperties base = TestSupport.properties(stack.baseUrl(), stack.baseUrl() + "/__upstream", stack.baseUrl() + "/jwks");
        VisualizerProperties properties = new VisualizerProperties(base.issuer(), base.targets(), base.defaultTarget(),
                Map.of("gateway", stack.baseUrl() + "/gw"), 4, "App Platform LB",
                lbHealthPath == null ? "" : stack.baseUrl() + lbHealthPath, "", base.upstreamAdminUrl(), base.jwksUrl(),
                base.allowedTenants(), base.maxTtl(), base.requestTimeout(), base.maxRequestsPerRun(), base.maxConcurrency(),
                base.access());
        return new HealthPoller(new VisualizerConfig().httpClient(), MAPPER, new EventHub(), properties, clock);
    }

    private static Map<String, ServiceStatus> byId(List<ServiceStatus> statuses) {
        return statuses.stream().collect(Collectors.toMap(ServiceStatus::id, Function.identity()));
    }

    private void instanceAnswers(String instanceId) {
        stack.stubFor(get("/gw/internal/status").willReturn(aResponse().withBody(status(instanceId))));
    }

    @Test
    void pollsEveryServiceAndDerivesDatabaseHealthFromTheGateways() {
        stack.stubFor(get("/g1/internal/status").willReturn(aResponse().withBody(status("gateway-1"))));
        stack.stubFor(get("/stats").willReturn(aResponse().withBody(HaproxyStatsTest.CSV)));
        stack.stubFor(get("/__upstream/health").willReturn(aResponse().withBody("{\"status\":\"healthy\"}")));
        HealthPoller poller = composePoller("/stats");

        poller.pollAndPublish();
        Map<String, ServiceStatus> statuses = byId(poller.latest());

        assertThat(statuses.get("lb").kind()).isEqualTo(Kind.LOAD_BALANCER);
        assertThat(statuses.get("lb").state()).isEqualTo(State.DEGRADED);
        assertThat(statuses.get("lb").info()).containsEntry("name", "HAProxy");
        assertThat(statuses.get("gateway-1").state()).isEqualTo(State.UP);
        assertThat(statuses.get("gateway-1").info()).containsEntry("revision", 7L).containsEntry("lb", "UP")
                .containsEntry("invalidEntries", 0).containsEntry("liveInstances", 2);
        assertThat(statuses.get("gateway-2").state()).isEqualTo(State.DOWN);
        assertThat(statuses.get("gateway-2").info()).containsEntry("lb", "DOWN 1/2");
        assertThat(statuses.get("upstream").state()).isEqualTo(State.UP);
        assertThat(statuses.get("postgres").state()).isEqualTo(State.UP);
        assertThat(statuses.get("valkey").state()).isEqualTo(State.UP);
    }

    @Test
    void everythingUnreachableIsDownOrUnknown() {
        stack.stubFor(get("/g1/internal/status").willReturn(aResponse().withBody("not json")));
        stack.stubFor(get("/stats").willReturn(aResponse().withBody("<html>")));
        Map<String, ServiceStatus> statuses = byId(composePoller("/stats").poll());

        assertThat(statuses.get("lb").state()).isEqualTo(State.DOWN);
        assertThat(statuses.get("gateway-1").state()).isEqualTo(State.DOWN);
        assertThat(statuses.get("gateway-1").info()).doesNotContainKey("lb");
        assertThat(statuses.get("upstream").state()).isEqualTo(State.DOWN);
        assertThat(statuses.get("postgres").state()).isEqualTo(State.UNKNOWN);
        assertThat(statuses.get("valkey").state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void missingStatsEndpointMeansTheLoadBalancerIsDown() {
        assertThat(byId(composePoller("/nothing").poll()).get("lb").state()).isEqualTo(State.DOWN);
    }

    @Test
    void discoversEveryInstanceBehindOneLoadBalancedManagementUrl() {
        stack.stubFor(get("/lbhealth").willReturn(aResponse().withBody("{\"status\":\"UP\"}")));
        HealthPoller poller = platformPoller("/lbhealth");
        instanceAnswers("gw-aaaa");
        poller.poll();
        instanceAnswers("gw-bbbb");
        clock.advance(Duration.ofSeconds(2));

        Map<String, ServiceStatus> statuses = byId(poller.poll());

        assertThat(statuses).containsKeys("gw-aaaa", "gw-bbbb").doesNotContainKey("gateway");
        assertThat(statuses.get("gw-aaaa").kind()).isEqualTo(Kind.GATEWAY);
        assertThat(statuses.get("lb").state()).isEqualTo(State.UP);
        assertThat(statuses.get("lb").detail()).isEqualTo("reachable");
        assertThat(statuses.get("lb").info()).containsEntry("name", "App Platform LB");
        assertThat(statuses.get("postgres").info()).containsEntry("reports", 2);
    }

    @Test
    void instancesNotSeenForTheTtlAreDropped() {
        HealthPoller poller = platformPoller(null);
        instanceAnswers("gw-old");
        poller.poll();

        instanceAnswers("gw-new");
        clock.advance(HealthPoller.INSTANCE_TTL.minusSeconds(1));
        assertThat(byId(poller.poll())).as("still within the TTL").containsKeys("gw-old", "gw-new");

        clock.advance(Duration.ofSeconds(2));
        assertThat(byId(poller.poll())).containsKey("gw-new").doesNotContainKey("gw-old");
    }

    @Test
    void unreachableManagementUrlShowsOneDownGatewayAndForgetsItsInstances() {
        instanceAnswers("gw-aaaa");
        HealthPoller poller = platformPoller("/lbhealth");
        poller.poll();

        stack.resetAll();
        Map<String, ServiceStatus> statuses = byId(poller.poll());

        assertThat(statuses).containsKey("gateway").doesNotContainKey("gw-aaaa");
        assertThat(statuses.get("gateway").state()).isEqualTo(State.DOWN);
        assertThat(statuses.get("lb").state()).isEqualTo(State.DOWN);
    }

    @Test
    void unmonitoredLoadBalancerIsUnknown() {
        assertThat(HealthPoller.loadBalancer("LB", false, null, null).state()).isEqualTo(State.UNKNOWN);
    }

    @Test
    void gatewayStatesFollowReadinessAndDependencies() throws Exception {
        JsonNode draining = MAPPER.readTree("{\"ready\":false}");
        JsonNode dbDown = MAPPER.readTree("{\"ready\":true,\"configStore\":\"DOWN\",\"rateLimiter\":\"normal\",\"invalidEntries\":2}");
        JsonNode limiterDegraded = MAPPER.readTree("{\"ready\":true,\"configStore\":\"UP\",\"rateLimiter\":\"degraded\"}");

        assertThat(HealthPoller.gateway("g", draining, "DOWN").state()).isEqualTo(State.DOWN);
        assertThat(HealthPoller.gateway("g", dbDown, "UP").state()).isEqualTo(State.DEGRADED);
        assertThat(HealthPoller.gateway("g", dbDown, "UP").info()).containsEntry("invalidEntries", 2);
        assertThat(HealthPoller.gateway("g", limiterDegraded, null).state()).isEqualTo(State.DEGRADED);
        assertThat(HealthPoller.derived("postgres", Kind.POSTGRES, List.of(dbDown, limiterDegraded), "configStore", "UP", "d").state())
                .isEqualTo(State.DEGRADED);
        assertThat(HealthPoller.derived("postgres", Kind.POSTGRES, List.of(dbDown), "configStore", "UP", "d").state())
                .isEqualTo(State.DOWN);
    }

    @Test
    void haproxyWithNoServersIsDegraded() {
        assertThat(HealthPoller.loadBalancer("HAProxy", true, Map.of(), null).state()).isEqualTo(State.DEGRADED);
        assertThat(HealthPoller.loadBalancer("HAProxy", true, Map.of("gateway-1", "UP"), null).state()).isEqualTo(State.UP);
        assertThat(State.DEGRADED.id()).isEqualTo("degraded");
        assertThat(Kind.LOAD_BALANCER.id()).isEqualTo("load_balancer");
    }
}
