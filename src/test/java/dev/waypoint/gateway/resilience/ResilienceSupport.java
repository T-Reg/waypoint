package dev.waypoint.gateway.resilience;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.TestGateway;
import dev.waypoint.gateway.support.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/** Multi-instance harness: several gateway contexts in one JVM against one PostgreSQL and Valkey. */
@Tag("resilience")
abstract class ResilienceSupport {

    static final Duration CONVERGE = Duration.ofSeconds(15);
    static WireMockServer upstream;

    final List<TestGateway> nodes = new ArrayList<>();
    String db;

    @BeforeAll
    static void startUpstream() {
        upstream = new WireMockServer(options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void freshDatabase() {
        db = Containers.newDatabase();
        Containers.flushValkey();
        upstream.resetAll();
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    @AfterEach
    void closeNodes() {
        nodes.forEach(TestGateway::close);
    }

    /** Short identity TTLs so key expiry is observable in seconds: retention 3 s, peers drop stale keys after 8 s. */
    TestGateway.Builder node(String name) {
        return TestGateway.builder(db).name(name)
                .property("gateway.identity.token-ttl", "2s")
                .property("gateway.auth.clock-skew", "1s")
                .property("gateway.identity.key-stale-after", "8s")
                .property("gateway.identity.heartbeat-interval", "300ms")
                .property("gateway.store.poll-interval", "500ms");
    }

    TestGateway start(TestGateway.Builder builder) {
        TestGateway gw = builder.startReady();
        nodes.add(gw);
        return gw;
    }

    TestGateway startNotReady(TestGateway.Builder builder) {
        TestGateway gw = builder.start();
        nodes.add(gw);
        return gw;
    }

    static String route(String prefix, String scopesJson) {
        return "{\"pathPrefix\":\"" + prefix + "\",\"upstreams\":[{\"url\":\"http://127.0.0.1:" + upstream.port()
                + "\"}],\"requiredScopes\":" + scopesJson + ",\"allowPrivateNetwork\":true}";
    }

    static void seedBasics(TestGateway gw, int rpm) {
        gw.upsertTenant("acme", "{\"allowedPathPrefixes\":[\"/svc\",\"/other\",\"/bad\"],\"requestsPerMinute\":" + rpm + "}");
        gw.upsertRoute("svc", route("/svc", "{\"*\":[]}"));
    }

    static String token() {
        return TestTokens.customer("acme", "web", "svc:read");
    }

    static void awaitSameRevision(Collection<TestGateway> gateways, long revision) {
        await().atMost(CONVERGE).until(() -> gateways.stream().allMatch(g -> g.revision() == revision));
    }

    static Set<String> kids(TestGateway gw) {
        return gw.jwks().path("keys").findValuesAsString("kid").stream().collect(Collectors.toSet());
    }

    static int upstreamCount() {
        return upstream.findAll(anyRequestedFor(anyUrl())).size();
    }

    static String lastIdentity() {
        var all = upstream.findAll(anyRequestedFor(anyUrl()));
        return all.get(all.size() - 1).getHeader("X-Gateway-Identity");
    }
}
