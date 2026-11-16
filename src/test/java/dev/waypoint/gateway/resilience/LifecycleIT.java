package dev.waypoint.gateway.resilience;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.gateway.support.TestGateway;
import dev.waypoint.gateway.support.TestTokens;
import dev.waypoint.gateway.support.UpstreamVerifier;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** R8–R12, R15: scale-up, graceful scale-down, crash, cross-instance identity, rotation broadcast, rolling restart. */
class LifecycleIT extends ResilienceSupport {

    @Test
    void r8_scaleUpServesCorrectlyOnceReadyAndPublishesItsKeyEverywhere() {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        seedBasics(a, 1000);
        long revision = a.revision();
        TestGateway c = startNotReady(node("c"));
        await().atMost(CONVERGE).until(c::isReady);
        assertThat(c.revision()).as("ready only with the full snapshot").isEqualTo(revision);
        assertThat(c.get("/svc/x", token()).statusCode()).isEqualTo(200);
        String cKid = c.status().path("kid").asString();
        await().atMost(CONVERGE).until(() -> kids(a).contains(cKid) && kids(b).contains(cKid));
    }

    @Test
    void r9_gracefulScaleDownDrainsInFlightAndKeepsKeyForTtl() {
        TestGateway a = start(node("a").property("gateway.lifecycle.shutdown-delay", "1s"));
        TestGateway b = start(node("b"));
        seedBasics(a, 1000);
        awaitSameRevision(List.of(a, b), a.revision());
        upstream.stubFor(any(urlPathMatching("/svc/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));
        String aKid = a.status().path("kid").asString();
        await().atMost(CONVERGE).until(() -> kids(b).contains(aKid));

        CompletableFuture<HttpResponse<String>> inFlight = CompletableFuture.supplyAsync(() -> a.get("/svc/slow", token()));
        await().atMost(CONVERGE).until(() -> upstreamCount() > 0);
        CompletableFuture<Void> closing = CompletableFuture.runAsync(a::close);
        await().atMost(Duration.ofSeconds(2)).until(() -> !a.isReady());

        assertThat(inFlight.join().statusCode()).isEqualTo(200);
        closing.join();
        assertThat(kids(b)).as("retired key stays published for token TTL + skew").contains(aKid);
        await().atMost(CONVERGE).until(() -> !kids(b).contains(aKid));
    }

    @Test
    void r10_crashedInstanceKeyLeavesJwksAfterHeartbeatTimeout() {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        seedBasics(a, 1000);
        String bKid = b.status().path("kid").asString();
        await().atMost(CONVERGE).until(() -> kids(a).contains(bKid));
        b.crash();
        assertThat(a.get("/svc/x", token()).statusCode()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(20)).until(() -> !kids(a).contains(bKid));
        assertThat(a.get("/svc/x", token()).statusCode()).isEqualTo(200);
    }

    @Test
    void r11_tokenFromOneInstanceVerifiesViaAnyInstancesJwks() throws Exception {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        seedBasics(a, 1000);
        String aKid = a.status().path("kid").asString();
        await().atMost(CONVERGE).until(() -> kids(b).contains(aKid));
        a.get("/svc/x", token());
        String token = lastIdentity();
        assertThat(UpstreamVerifier.remote(b.managementPort(), "svc").verify(token).getClaim("tid")).isEqualTo("acme");
        TestGateway c = start(node("c"));
        await().atMost(CONVERGE).until(() -> kids(c).contains(aKid));
        assertThat(UpstreamVerifier.remote(c.managementPort(), "svc").accepts(token)).isTrue();
    }

    @Test
    void r12_rotationIsBroadcastToEveryInstance() throws Exception {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        TestGateway c = start(node("c"));
        List<TestGateway> all = List.of(a, b, c);
        List<String> before = all.stream().map(g -> g.status().path("kid").asString()).toList();
        assertThat(a.rotateKeys().statusCode()).isEqualTo(200);
        await().atMost(CONVERGE).until(() -> all.stream().noneMatch(g -> before.contains(g.status().path("kid").asString())));
        List<String> after = all.stream().map(g -> g.status().path("kid").asString()).toList();
        await().atMost(CONVERGE).until(() -> all.stream().allMatch(g -> kids(g).containsAll(after) && kids(g).containsAll(before)));
        seedBasics(b, 1000);
        b.get("/svc/x", token());
        assertThat(SignedJWT.parse(lastIdentity()).getHeader().getKeyID()).isEqualTo(after.get(1));
    }

    /** R15, the headline test: rolling restart under continuous load with zero failed requests. */
    @Test
    void r15_rollingRestartUnderLoadHasZeroErrors() throws Exception {
        TestGateway.Builder[] builders = new TestGateway.Builder[3];
        List<TestGateway> live = new ArrayList<>();
        try (TestLoadBalancer lb = new TestLoadBalancer(Duration.ofMillis(100))) {
            for (int i = 0; i < 3; i++) {
                builders[i] = node("n" + i).property("gateway.lifecycle.shutdown-delay", "1s");
                TestGateway gw = start(builders[i]);
                live.add(gw);
                lb.add(gw);
            }
            seedBasics(live.get(0), 100_000);
            awaitSameRevision(live, live.get(0).revision());
            await().atMost(CONVERGE).until(() -> lb.ok.get() >= 0 && live.stream().allMatch(TestGateway::isReady));

            AtomicBoolean loading = new AtomicBoolean(true);
            String token = TestTokens.customer("acme", "web", "");
            ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
            for (int t = 0; t < 4; t++) {
                clients.submit(() -> {
                    while (loading.get()) {
                        lb.send("/svc/x", token);
                    }
                });
            }
            await().atMost(CONVERGE).until(() -> lb.ok.get() > 50);

            for (int i = 0; i < 3; i++) {
                TestGateway old = live.get(i);
                old.close();
                lb.remove(old);
                TestGateway replacement = start(builders[i]);
                lb.add(replacement);
                live.set(i, replacement);
                replacement.awaitReady();
                if (i == 1) {
                    live.get(0).upsertTenant("midroll", "{\"allowedPathPrefixes\":[\"/svc\"],\"requestsPerMinute\":5}");
                }
                long okBefore = lb.ok.get();
                await().atMost(CONVERGE).until(() -> lb.ok.get() > okBefore + 50);
            }
            loading.set(false);
            clients.shutdown();
            assertThat(clients.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            assertThat(lb.failures).as("zero non-2xx during the roll").isEmpty();
            assertThat(lb.ok.get()).isGreaterThan(150);
            long revision = live.get(0).revision();
            awaitSameRevision(live, revision);
            Set<Integer> statuses = Set.of(live.get(2).admin("GET", "/admin/tenants/midroll",
                    TestTokens.admin("alice", "admin:tenants"), null, Map.of()).statusCode());
            assertThat(statuses).containsExactly(200);
        }
    }
}
