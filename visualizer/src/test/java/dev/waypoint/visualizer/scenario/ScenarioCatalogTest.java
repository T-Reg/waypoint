package dev.waypoint.visualizer.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.support.TestSupport;
import dev.waypoint.visualizer.token.TokenFault;
import dev.waypoint.visualizer.traffic.RequestSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioCatalogTest {

    private final VisualizerProperties properties = TestSupport.properties("http://gw", "http://up", "http://jwks");
    private final ScenarioCatalog catalog = new ScenarioCatalog(properties);

    @Test
    void everyScenarioIsMintableAndWithinLimits() {
        assertThat(catalog.all()).hasSizeGreaterThanOrEqualTo(10);
        for (Scenario scenario : catalog.all()) {
            assertThat(scenario.title()).isNotBlank();
            assertThat(scenario.watch()).isNotBlank();
            assertThat(scenario.expect()).isNotBlank();
            assertThat(scenario.totalRequests()).isBetween(1, properties.maxRequestsPerRun());
            for (Scenario.Step step : scenario.steps()) {
                assertThat(properties.allowedTenants()).contains(step.request().tenant());
                assertThat(properties.targets()).containsKey(step.request().target());
            }
        }
    }

    @Test
    void burstScenarioExceedsGlobexQuota() {
        Scenario burst = catalog.find("rate-limit-burst").orElseThrow();

        assertThat(burst.totalRequests()).isEqualTo(80);
        assertThat(burst.steps().getFirst().request().tenant()).isEqualTo("globex");
        assertThat(burst.steps().getFirst().interval()).isZero();
    }

    @Test
    void sharedQuotaHitsBothGatewaysAtOnce() {
        Scenario shared = catalog.find("shared-quota").orElseThrow();

        assertThat(shared.parallel()).isTrue();
        assertThat(shared.steps()).extracting(step -> step.request().target()).containsExactly("gateway-1", "gateway-2");
    }

    @Test
    void withoutPerInstanceTargetsTheSharedQuotaScenarioIsLeftOutAndTextsNameTheLoadBalancer() {
        VisualizerProperties platform = new VisualizerProperties(properties.issuer(),
                Map.of("lb", "https://app.example", "internal", "http://gateway:8080"), "lb", Map.of("gateway", "http://gateway:8082"),
                4, "App Platform LB", "", "", properties.upstreamAdminUrl(), properties.jwksUrl(), properties.allowedTenants(),
                properties.maxTtl(), properties.requestTimeout(), 200, 20, properties.access());
        ScenarioCatalog platformCatalog = new ScenarioCatalog(platform);

        assertThat(platformCatalog.find("shared-quota")).isEmpty();
        assertThat(platformCatalog.find("rate-limit-burst")).isPresent();
        assertThat(platformCatalog.find("load-balancing").orElseThrow().summary()).contains("App Platform LB");
        assertThat(catalog.find("shared-quota")).isPresent();
    }

    @Test
    void unknownScenarioIsEmpty() {
        assertThat(catalog.find("nope")).isEmpty();
    }

    @Test
    void refusesScenariosLargerThanTheRunLimit() {
        VisualizerProperties tight = new VisualizerProperties(properties.issuer(), properties.targets(), "lb",
                properties.gatewayManagement(), properties.managementProbes(), properties.loadBalancerName(), properties.loadBalancerHealthUrl(), properties.haproxyStatsUrl(), properties.upstreamAdminUrl(),
                properties.jwksUrl(), properties.allowedTenants(), properties.maxTtl(), properties.requestTimeout(),
                10, 5, properties.access());

        assertThatThrownBy(() -> new ScenarioCatalog(tight)).hasMessageContaining("max-requests-per-run");
    }

    @Test
    void stepsValidateTheirShape() {
        RequestSpec spec = new RequestSpec("lb", "GET", "/x", "acme", null, null, TokenFault.NONE, Map.of(), null);

        assertThatThrownBy(() -> Scenario.Step.burst("x", spec, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scenario.Step("x", spec, 1, 1, Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Scenario("e", "t", "c", "s", "w", "x", false, List.of()))
                .hasMessageContaining("no steps");
        assertThat(Scenario.Step.once("x", spec).interval()).isPositive();
    }
}
