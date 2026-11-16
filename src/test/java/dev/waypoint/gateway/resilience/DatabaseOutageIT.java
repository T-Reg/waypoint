package dev.waypoint.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.TestGateway;
import dev.waypoint.gateway.support.UpstreamVerifier;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/** R6, R7, R20: the request path never depends on the DB; startup waits for it; a partitioned instance drains itself. */
class DatabaseOutageIT extends ResilienceSupport {

    @Test
    void r6_dbOutageWhileRunning() {
        // Production-like key staleness: a short outage must not affect readiness.
        TestGateway a = start(node("a").property("gateway.identity.key-stale-after", "10m"));
        TestGateway b = start(node("b").property("gateway.identity.key-stale-after", "10m"));
        seedBasics(a, 100_000);
        awaitSameRevision(List.of(a, b), a.revision());
        await().atMost(CONVERGE).until(() -> kids(b).contains(a.status().path("kid").asString()));

        Containers.pause(Containers.POSTGRES);
        try {
            for (int i = 0; i < 20; i++) {
                assertThat((i % 2 == 0 ? a : b).get("/svc/x", token()).statusCode()).isEqualTo(200);
            }
            a.get("/svc/x", token());
            String jwksDuringOutage = b.jwksJson();
            assertThat(UpstreamVerifier.fixed(jwksDuringOutage, "svc").accepts(lastIdentity())).isTrue();
            assertThat(a.isReady()).as("readiness stays up during a DB outage").isTrue();
            assertThat(a.putTenant("new", "{\"allowedPathPrefixes\":[\"/svc\"],\"requestsPerMinute\":5}").statusCode()).isEqualTo(503);
            assertThat(b.isReady()).isTrue();
            await().atMost(CONVERGE).until(() -> "DOWN".equals(a.status().path("configStore").asString()));
        } finally {
            Containers.unpause(Containers.POSTGRES);
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> a.putTenant("new", "{\"allowedPathPrefixes\":[\"/svc\"],\"requestsPerMinute\":5}").statusCode() == 201);
        awaitSameRevision(List.of(a, b), a.revision());
        await().atMost(CONVERGE).until(() -> "UP".equals(b.status().path("configStore").asString()));
    }

    @Test
    void r7_dbDownAtStartupStaysNotReadyThenRecovers() {
        TestGateway a = start(node("a"));
        seedBasics(a, 1000);
        long revision = a.revision();
        Containers.pause(Containers.POSTGRES);
        TestGateway c;
        try {
            c = startNotReady(node("c"));
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> !c.isReady());
            assertThat(c.get("/svc/x", token()).statusCode()).as("serves no traffic").isEqualTo(503);
        } finally {
            Containers.unpause(Containers.POSTGRES);
        }
        c.awaitReady();
        assertThat(c.revision()).isEqualTo(revision);
        assertThat(c.get("/svc/x", token()).statusCode()).isEqualTo(200);
    }

    @Test
    void r20_partitionedInstanceKeepsServingThenDrainsBeforePeersDropItsKey() throws IOException {
        ToxiproxyContainer toxiproxy = Containers.toxiproxy();
        ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        Proxy pg = client.createProxy("pg-r20-" + System.nanoTime() % 100000, "0.0.0.0:8666", "postgres:5432");
        try {
            String viaProxy = db.replaceFirst("//[^/]+/", "//" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666) + "/");
            TestGateway a = start(node("a"));
            TestGateway b = start(node("b").property("spring.datasource.url", viaProxy));
            seedBasics(a, 100_000);
            awaitSameRevision(List.of(a, b), a.revision());
            String bKid = b.status().path("kid").asString();
            await().atMost(CONVERGE).until(() -> kids(a).contains(bKid));

            pg.disable();
            assertThat(b.get("/svc/x", token()).statusCode()).isEqualTo(200);
            assertThat(UpstreamVerifier.fixed(a.jwksJson(), "svc").accepts(lastIdentity())).as("B's token verifies via A").isTrue();

            await().atMost(Duration.ofSeconds(15)).until(() -> !b.isReady());
            b.get("/svc/x", token());
            String mintedWhenDraining = lastIdentity();
            assertThat(kids(a)).as("A still publishes B's key when B goes not-ready").contains(bKid);
            assertThat(UpstreamVerifier.fixed(a.jwksJson(), "svc").accepts(mintedWhenDraining)).isTrue();
            await().atMost(Duration.ofSeconds(15)).until(() -> !kids(a).contains(bKid));

            pg.enable();
            b.awaitReady();
        } finally {
            pg.delete();
        }
    }
}
