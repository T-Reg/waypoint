package dev.waypoint.visualizer.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.visualizer.support.TestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The DigitalOcean profile binds, requires a password, and offers only the targets App Platform can address. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("app-platform")
class AppPlatformProfileIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("VISUALIZER_LB_URL", () -> "http://127.0.0.1:1");
        registry.add("visualizer.issuer.key", TestSupport.ISSUER_KEY::toJSONString);
        registry.add("visualizer.access.password", () -> "s3cret");
        registry.add("visualizer.health-interval", () -> "1h");
        registry.add("server.port", () -> 0);
    }

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path, boolean withCredentials) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (withCredentials) {
            String basic = Base64.getEncoder().encodeToString("demo:s3cret".getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + basic);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requiresTheAccessPassword() throws Exception {
        assertThat(get("/api/config", false).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/health", false).statusCode()).isEqualTo(200);
    }

    @Test
    void offersTheLoadBalancerAndTheInternalServiceOnly() throws Exception {
        JsonNode config = JsonMapper.builder().build().readTree(get("/api/config", true).body());

        assertThat(config.path("targets").toString()).isEqualTo("[\"internal\",\"lb\"]");
        assertThat(config.path("loadBalancerName").asString()).isEqualTo("App Platform LB");
        assertThat(config.path("scenarios").toString()).doesNotContain("shared-quota").contains("rate-limit-burst");
    }
}
