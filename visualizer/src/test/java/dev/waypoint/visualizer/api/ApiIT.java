package dev.waypoint.visualizer.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.waypoint.visualizer.scenario.Pacer;
import dev.waypoint.visualizer.support.TestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIT {

    private static final WireMockServer STACK = new WireMockServer(options().dynamicPort());
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static {
        STACK.start();
    }

    @TestConfiguration
    static class NoPacing {
        @Bean
        @Primary
        Pacer noPause() {
            return interval -> { };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("visualizer.issuer.key", TestSupport.ISSUER_KEY::toJSONString);
        registry.add("visualizer.targets.lb", STACK::baseUrl);
        registry.add("visualizer.targets.gateway-1", STACK::baseUrl);
        registry.add("visualizer.targets.gateway-2", STACK::baseUrl);
        registry.add("visualizer.gateway-management.gateway-1", () -> STACK.baseUrl() + "/g1");
        registry.add("visualizer.gateway-management.gateway-2", () -> STACK.baseUrl() + "/g2");
        registry.add("visualizer.haproxy-stats-url", () -> STACK.baseUrl() + "/stats");
        registry.add("visualizer.upstream-admin-url", () -> STACK.baseUrl() + "/__admin");
        registry.add("visualizer.jwks-url", () -> STACK.baseUrl() + "/jwks");
        registry.add("visualizer.health-interval", () -> "1h");
        registry.add("server.port", () -> 0);
    }

    @AfterAll
    static void stopStack() {
        STACK.stop();
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void stubGateway() {
        STACK.resetAll();
        STACK.stubFor(any(urlPathEqualTo("/weather/today")).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/problem+json").withHeader("X-Served-By", "gateway-1")
                .withBody("{\"status\":403,\"code\":\"policy.path_denied\",\"detail\":\"path not allowed\"}")));
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return MAPPER.readTree(response.body());
    }

    private static String send(String extra) {
        return "{\"request\":{\"method\":\"GET\",\"path\":\"/weather/today\",\"tenant\":\"globex\",\"scope\":\"weather:read\"}" + extra + "}";
    }

    @Test
    void servesThePage() throws Exception {
        HttpResponse<String> page = call("GET", "/", null);

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<title>Waypoint Visualizer</title>");
        HttpResponse<String> script = call("GET", "/app.js", null);
        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.headers().firstValue("Cache-Control")).hasValue("no-cache");
        assertThat(script.headers().firstValue("Last-Modified")).isEmpty();
    }

    @Test
    void configListsScenariosTargetsAndFaults() throws Exception {
        JsonNode config = json(call("GET", "/api/config", null));

        assertThat(config.path("defaultTarget").asString()).isEqualTo("lb");
        assertThat(config.path("loadBalancerName").asString()).isEqualTo("HAProxy");
        assertThat(config.path("targets").toString()).isEqualTo("[\"gateway-1\",\"gateway-2\",\"lb\"]");
        assertThat(config.path("scenarios").size()).isGreaterThanOrEqualTo(10);
        assertThat(config.path("faults").toString()).contains("ALG_NONE");
        assertThat(config.path("scenarios").get(0).path("steps").get(0).path("request").path("fault").asString()).isEqualTo("NONE");
    }

    @Test
    void singleRequestIsExplainedSynchronouslyAndKeptInHistory() throws Exception {
        HttpResponse<String> response = call("POST", "/api/requests", send(""));
        JsonNode result = json(response);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result.path("target").asString()).isEqualTo("lb");
        assertThat(result.path("stage").asString()).isEqualTo("allowlist");
        assertThat(result.path("outcome").asString()).isEqualTo("REJECTED");
        assertThat(result.path("servedBy").asString()).isEqualTo("gateway-1");
        assertThat(result.path("upstream").path("received").asBoolean()).isFalse();

        JsonNode recent = json(call("GET", "/api/requests", null));
        assertThat(recent.toString()).contains(result.path("requestId").asString(""), "\"seq\":" + result.path("seq").asLong());
    }

    @Test
    void burstRunsAsynchronously() throws Exception {
        HttpResponse<String> response = call("POST", "/api/requests", send(",\"count\":5,\"concurrency\":5"));
        String runId = json(response).path("runId").asString();

        assertThat(response.statusCode()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long count = 0;
            for (JsonNode r : json(call("GET", "/api/requests", null))) {
                if (runId.equals(r.path("runId").asString(null))) {
                    count++;
                }
            }
            assertThat(count).isEqualTo(5);
        });
    }

    @Test
    void scenariosRunByIdAndUnknownOnesAre404() throws Exception {
        assertThat(call("POST", "/api/scenarios/no-token/run", null).statusCode()).isEqualTo(202);

        HttpResponse<String> missing = call("POST", "/api/scenarios/nope/run", null);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/problem+json"));
    }

    @Test
    void invalidRequestsAre400() throws Exception {
        HttpResponse<String> badPath = call("POST", "/api/requests",
                "{\"request\":{\"path\":\"weather\",\"tenant\":\"acme\"}}");
        assertThat(badPath.statusCode()).isEqualTo(400);

        assertThat(json(call("POST", "/api/requests", "{}")).path("detail").asString()).isEqualTo("request is required");
        assertThat(call("POST", "/api/requests", send(",\"count\":0")).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/requests", send(",\"unknown\":1")).statusCode()).isEqualTo(400);
        HttpResponse<String> badTenant = call("POST", "/api/requests",
                "{\"request\":{\"path\":\"/weather\",\"tenant\":\"evil\"}}");
        assertThat(json(badTenant).path("detail").asString()).contains("allowed-tenants");
        HttpResponse<String> badTarget = call("POST", "/api/requests",
                "{\"request\":{\"target\":\"gateway-9\",\"path\":\"/weather\",\"tenant\":\"acme\"}}");
        assertThat(json(badTarget).path("detail").asString()).contains("target");
    }

    @Test
    void healthEndpointReturnsTheLatestPoll() throws Exception {
        STACK.stubFor(get(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(404)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(json(call("GET", "/api/health", null)).size()).isEqualTo(6));
    }

    @Test
    void streamsRequestEventsOverSse() throws Exception {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpRequest subscribe = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/events")).build();
        var stream = HTTP.sendAsync(subscribe, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().forEach(lines::add));
        try {
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                call("POST", "/api/requests", send(""));
                String line;
                while ((line = lines.poll(200, TimeUnit.MILLISECONDS)) != null) {
                    if (line.equals("event:request")) {
                        return true;
                    }
                }
                return false;
            });
        } finally {
            stream.cancel(true);
        }
    }
}
