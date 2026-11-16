package dev.waypoint.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.TestGateway;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/** R16–R19, R23: one quota across all instances, stable across scaling, connected before ready, degraded (not failed) when Valkey is down or slow. */
class SharedQuotaIT extends ResilienceSupport {

    static List<Integer> fire(List<TestGateway> gateways, int count) {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HttpResponse<String> response = gateways.get(i % gateways.size()).get("/svc/x", token());
            statuses.add(response.statusCode());
            if (response.statusCode() == 429) {
                int retryAfter = Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow());
                assertThat(retryAfter).isBetween(1, 60);
            }
        }
        return statuses;
    }

    static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    @Test
    void r16_quotaIsSharedAcrossInstances() {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        TestGateway c = start(node("c"));
        seedBasics(a, 60);
        awaitSameRevision(List.of(a, b, c), a.revision());
        upstream.resetRequests();
        List<Integer> statuses = fire(List.of(a, b, c), 100);
        assertThat(count(statuses, 200)).isEqualTo(60);
        assertThat(count(statuses, 429)).isEqualTo(40);
        assertThat(upstreamCount()).isEqualTo(60);
    }

    @Test
    void r23_instanceConnectsToValkeyBeforeItIsReady() throws Exception {
        TestGateway a = start(node("a").property("spring.data.redis.client-name", "r23-a"));
        assertThat(Containers.VALKEY.execInContainer("valkey-cli", "CLIENT", "LIST").getStdout())
                .as("connected during startup, before any request").contains("name=r23-a");
        assertThat(a.status().path("rateLimiter").asString()).isEqualTo("normal");
    }

    @Test
    void r17_scalingDoesNotResetQuota() {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        seedBasics(a, 60);
        awaitSameRevision(List.of(a, b), a.revision());
        assertThat(count(fire(List.of(a, b), 50), 200)).isEqualTo(50);
        TestGateway c = start(node("c"));
        b.close();
        assertThat(count(fire(List.of(a, c), 20), 200)).isEqualTo(10);
    }

    @Test
    void r18_valkeyOutageDegradesToLocalShareWithoutErrors() {
        TestGateway a = start(node("a").property("gateway.rate-limit.probe-interval", "500ms"));
        TestGateway b = start(node("b"));
        TestGateway c = start(node("c"));
        seedBasics(a, 60);
        awaitSameRevision(List.of(a, b, c), a.revision());
        await().atMost(CONVERGE).until(() -> a.status().path("liveInstances").asInt() == 3);

        Containers.pause(Containers.VALKEY);
        try {
            List<Integer> statuses = fire(List.of(a), 30);
            assertThat(statuses).noneMatch(s -> s >= 500);
            assertThat(count(statuses, 200)).as("RPM / live instances = 60 / 3").isEqualTo(20);
            assertThat(a.status().path("rateLimiter").asString()).isEqualTo("degraded");
            assertThat(a.bean(MeterRegistry.class).get("ratelimit.degraded").gauge().value()).isEqualTo(1.0);
        } finally {
            Containers.unpause(Containers.VALKEY);
        }
        Containers.flushValkey();
        await().atMost(CONVERGE).until(() -> {
            a.get("/svc/x", token());
            return "normal".equals(a.status().path("rateLimiter").asString());
        });
        assertThat(a.bean(MeterRegistry.class).get("ratelimit.degraded").gauge().value()).isZero();
    }

    @Test
    void r19_slowValkeyDegradesInsteadOfBlocking() throws IOException {
        ToxiproxyContainer toxiproxy = Containers.toxiproxy();
        ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        Proxy valkey = client.createProxy("valkey-r19-" + System.nanoTime() % 100000, "0.0.0.0:8667", "valkey:6379");
        try {
            TestGateway a = start(node("a").property("spring.data.redis.url",
                    "redis://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8667)));
            seedBasics(a, 1000);
            assertThat(a.get("/svc/x", token()).statusCode()).isEqualTo(200);
            assertThat(a.status().path("rateLimiter").asString()).isEqualTo("normal");

            valkey.toxics().latency("slow", ToxicDirection.DOWNSTREAM, 1000);
            long start = System.nanoTime();
            List<Integer> statuses = fire(List.of(a), 10);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(statuses).allMatch(s -> s == 200);
            assertThat(elapsed).as("10 requests never wait on a 1 s Valkey").isLessThan(Duration.ofSeconds(3));
            assertThat(a.status().path("rateLimiter").asString()).isEqualTo("degraded");
            valkey.toxics().get("slow").remove();
        } finally {
            valkey.delete();
        }
    }
}
