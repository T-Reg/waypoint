package dev.waypoint.visualizer.scenario;

import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.scenario.Scenario.Step;
import dev.waypoint.visualizer.token.TokenFault;
import dev.waypoint.visualizer.traffic.RequestSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Built-in demos. Tenants and routes match deploy/seed.yaml. */
@Component
public class ScenarioCatalog {

    private static final String WEATHER = "/weather/today";
    private static final String READ = "weather:read";

    private final Map<String, Scenario> scenarios = new LinkedHashMap<>();

    public ScenarioCatalog(VisualizerProperties properties) {
        String lb = properties.defaultTarget();
        boolean perInstanceTargets = properties.targets().containsKey("gateway-1") && properties.targets().containsKey("gateway-2");
        for (Scenario scenario : build(lb, properties.loadBalancerName(), perInstanceTargets)) {
            if (scenario.totalRequests() > properties.maxRequestsPerRun()) {
                throw new IllegalArgumentException("scenario " + scenario.id() + " exceeds visualizer.max-requests-per-run");
            }
            if (scenarios.putIfAbsent(scenario.id(), scenario) != null) {
                throw new IllegalArgumentException("duplicate scenario id " + scenario.id());
            }
        }
    }

    public List<Scenario> all() {
        return List.copyOf(scenarios.values());
    }

    public Optional<Scenario> find(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    private static RequestSpec get(String target, String tenant, String scope, String path) {
        return new RequestSpec(target, "GET", path, tenant, "web-app", scope, TokenFault.NONE, Map.of(), null);
    }

    private static RequestSpec faulty(String target, TokenFault fault) {
        return new RequestSpec(target, "GET", WEATHER, "acme", "web-app", READ, fault, Map.of(), null);
    }

    private static List<Scenario> build(String lb, String lbName, boolean perInstanceTargets) {
        List<Scenario> list = new ArrayList<>();
        RequestSpec acmeRead = get(lb, "acme", "weather:read maps:read", WEATHER);

        list.add(new Scenario("happy-path", "Allowed request", "Basics",
                "Acme reads /weather/today with a valid token and the right scope.",
                "Every stage passes. The upstream gets a fresh gateway-signed X-Gateway-Identity (aud=weather-svc) "
                        + "and never sees the customer's Authorization header.",
                "1 × 200", false, List.of(Step.once("Acme GET /weather/today", acmeRead))));

        list.add(new Scenario("rate-limit-burst", "Burst: rate limiting", "Rate limiting",
                "Globex (60 requests/min) fires 80 requests at once from the same caller.",
                "The first 60 pass, even though they're spread across the gateway instances: the quota is one "
                        + "sliding window in Valkey. The rest stop at Quota with 429 and a Retry-After header. "
                        + "Rejected requests never reach the upstream. Run it again within a minute and all 80 are rejected. "
                        + "If a gateway briefly loses Valkey it falls back to a local quota (RPM ÷ instances), "
                        + "so a few extra can pass.",
                "60 × 200, 20 × 429", false,
                List.of(Step.burst("80 × Globex GET /weather/today", get(lb, "globex", READ, WEATHER), 80, 16))));

        if (perInstanceTargets) {
            list.add(new Scenario("shared-quota", "Shared quota across instances", "Rate limiting",
                    "Initech (30 requests/min) sends 25 requests straight to gateway-1 and 25 to gateway-2 at the same time.",
                    "Both instances share one sliding window in Valkey, so exactly 30 pass in total, not 30 per instance.",
                    "30 × 200, 20 × 429", true,
                    List.of(Step.burst("25 × Initech → gateway-1", get("gateway-1", "initech", READ, WEATHER), 25, 10),
                            Step.burst("25 × Initech → gateway-2", get("gateway-2", "initech", READ, WEATHER), 25, 10))));
        }

        list.add(new Scenario("load-balancing", "Load balancing", "Basics",
                "Twelve requests one after another through " + lbName + ".",
                "Requests spread across the gateway instances; each lane lights up for the instance that answered. "
                        + "Restart a gateway and watch " + lbName + " take it out of rotation.",
                "12 × 200, split across both gateways", false,
                List.of(Step.paced("12 × Acme GET /weather/today", acmeRead, 12, 400))));

        list.add(new Scenario("no-token", "Missing token", "Authentication",
                "A request with no Authorization header.",
                "Stops at Authenticate with 401 auth.missing and WWW-Authenticate: Bearer.",
                "1 × 401", false, List.of(Step.once("No token", faulty(lb, TokenFault.MISSING)))));

        list.add(new Scenario("forged-tenant", "Forged tenant", "Authentication",
                "A valid Acme token whose payload is edited after signing to claim a different tenant.",
                "The Ed25519 signature no longer matches, so the gateway rejects it before reading tid.",
                "1 × 401", false, List.of(Step.once("Tampered payload", faulty(lb, TokenFault.TAMPERED)))));

        list.add(new Scenario("token-attacks", "Token attacks", "Authentication",
                "Expired, not-yet-valid, wrong-audience, alg=none and HS256 alg-confusion tokens.",
                "All five stop at Authenticate. The gateway pins alg=Ed25519 and checks typ, iss, aud, exp and nbf.",
                "5 × 401", false, List.of(
                        Step.once("Expired", faulty(lb, TokenFault.EXPIRED)),
                        Step.once("Not yet valid", faulty(lb, TokenFault.NOT_YET_VALID)),
                        Step.once("aud=gateway-admin", faulty(lb, TokenFault.WRONG_AUDIENCE)),
                        Step.once("alg=none", faulty(lb, TokenFault.ALG_NONE)),
                        Step.once("HS256 with public key", faulty(lb, TokenFault.ALG_HS256)))));

        list.add(new Scenario("unknown-tenant", "Unknown tenant", "Authentication",
                "A correctly signed token for tenant 'umbrella', which the gateway has no config for.",
                "Signature checks pass, then the tenant lookup fails: 401 auth.unknown_tenant.",
                "1 × 401", false, List.of(Step.once("Umbrella GET /weather/today", get(lb, "umbrella", READ, WEATHER)))));

        list.add(new Scenario("policy-denials", "Policy denials", "Policy",
                "Path outside the allowlist, missing scope, and an unconfigured HTTP method.",
                "Each stops at a different stage: Allowlist (403), Scope (403) and Method (405 with an Allow header).",
                "403, 403, 405", false, List.of(
                        Step.once("Globex GET /maps (not allowlisted)", get(lb, "globex", READ, "/maps/berlin")),
                        Step.once("Acme POST /weather without weather:write",
                                new RequestSpec(lb, "POST", WEATHER, "acme", "batch-job", READ, TokenFault.NONE, Map.of(),
                                        "{\"temp\":21}")),
                        Step.once("Acme DELETE /weather",
                                new RequestSpec(lb, "DELETE", WEATHER, "acme", "web-app", "weather:read weather:write",
                                        TokenFault.NONE, Map.of(), null)))));

        list.add(new Scenario("path-tricks", "Path traversal tricks", "Policy",
                "Classic parser-confusion paths that try to escape /weather into /maps.",
                "The normalizer rejects all four with 400 request.bad_path before auth or routing see them.",
                "4 × 400", false, List.of(
                        Step.once("/weather/..;/maps", get(lb, "globex", READ, "/weather/..;/maps")),
                        Step.once("/weather/%2e%2e/maps", get(lb, "globex", READ, "/weather/%2e%2e/maps")),
                        Step.once("/weather/%252e%252e/maps", get(lb, "globex", READ, "/weather/%252e%252e/maps")),
                        Step.once("/weather%2Fmaps", get(lb, "globex", READ, "/weather%2Fmaps")))));

        list.add(new Scenario("header-spoofing", "Header spoofing", "Proxy",
                "Acme sends a forged X-Gateway-Identity, a fake X-Forwarded-For and Forwarded header.",
                "The request succeeds, but open 'What the upstream saw': the forged headers are gone, replaced by a "
                        + "gateway-signed identity and a rebuilt X-Forwarded-For.",
                "1 × 200", false, List.of(Step.once("Acme GET with spoofed headers",
                        new RequestSpec(lb, "GET", WEATHER, "acme", "web-app", READ, TokenFault.NONE, Map.of(
                                "X-Gateway-Identity", "eyJhbGciOiJub25lIn0.eyJ0aWQiOiJnbG9iZXgifQ.",
                                "X-Forwarded-For", "6.6.6.6",
                                "Forwarded", "for=6.6.6.6;host=evil.example",
                                "X-Real-IP", "6.6.6.6"), null)))));

        list.add(new Scenario("security-tour", "Full tour", "Tour",
                "One of everything, in pipeline order, so you can watch each stage reject something.",
                "Requests stop progressively further along the pipeline until the last one reaches the upstream.",
                "400, 401, 401, 403, 405, 403, 200", false, List.of(
                        Step.once("Ambiguous path → Normalize", get(lb, "acme", READ, "/weather/..;/maps")),
                        Step.once("No token → Authenticate", faulty(lb, TokenFault.MISSING)),
                        Step.once("Forged token → Authenticate", faulty(lb, TokenFault.TAMPERED)),
                        Step.once("Not allowlisted → Allowlist", get(lb, "globex", READ, "/maps/berlin")),
                        Step.once("DELETE → Method", new RequestSpec(lb, "DELETE", WEATHER, "acme", "web-app", READ,
                                TokenFault.NONE, Map.of(), null)),
                        Step.once("POST without write scope → Scope", new RequestSpec(lb, "POST", WEATHER, "acme",
                                "web-app", READ, TokenFault.NONE, Map.of(), "{}")),
                        Step.once("Allowed → Upstream", acmeRead))));

        list.add(new Scenario("mixed-traffic", "Mixed traffic", "Tour",
                "A steady mix of good and bad requests from several tenants through the load balancer.",
                "Watch both gateways share the load while each request stops where its policy says.",
                "Mostly 200 with a sprinkling of 401/403/405", true, List.of(
                        Step.paced("Acme reads", acmeRead, 20, 300),
                        Step.paced("Acme maps", get(lb, "acme", "maps:read", "/maps/paris"), 8, 750),
                        Step.paced("Loadtest reads", get(lb, "loadtest", READ, WEATHER), 12, 500),
                        Step.paced("Globex tries /maps", get(lb, "globex", READ, "/maps/berlin"), 4, 1500),
                        Step.paced("Expired tokens", faulty(lb, TokenFault.EXPIRED), 4, 1500),
                        Step.paced("Acme DELETEs", new RequestSpec(lb, "DELETE", WEATHER, "acme", "web-app", READ,
                                TokenFault.NONE, Map.of(), null), 2, 3000))));
        return list;
    }
}
