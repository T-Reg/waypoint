package dev.waypoint.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.waypoint.gateway.support.Db;
import dev.waypoint.gateway.support.TestGateway;
import dev.waypoint.gateway.support.TestTokens;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** R1–R5, R13, R14: persistence, seeding, propagation, missed notifications, concurrent writes, bad rows, migrations. */
class ConfigPropagationIT extends ResilienceSupport {

    @Test
    void r1_configSurvivesRestart() {
        TestGateway a = start(node("a"));
        seedBasics(a, 1000);
        a.close();
        TestGateway a2 = start(node("a2"));
        assertThat(a2.get("/svc/x", token()).statusCode()).isEqualTo(200);
        assertThat(new Db(db).query("SELECT action FROM config_audit WHERE entity_id = 'svc'")).isNotEmpty();
    }

    @Test
    void r2_seedNeverClobbersRuntimeChanges() {
        TestGateway.Builder seeded = node("a").property("gateway.seed.if-empty", true).property("gateway.seed.file", "classpath:seed-test.yaml");
        TestGateway a = start(seeded);
        HttpResponse<String> seededRoute = a.admin("GET", "/admin/routes/weather", TestTokens.admin("alice", "admin:routes"), null, Map.of());
        assertThat(TestGateway.json(seededRoute).path("spec").path("timeoutMs").asInt()).isEqualTo(10000);
        a.upsertRoute("weather", "{\"pathPrefix\":\"/weather\",\"upstreams\":[{\"url\":\"https://93.184.216.34\"}],"
                + "\"requiredScopes\":{\"GET\":[\"weather:read\"]},\"timeoutMs\":2500}");
        a.close();
        TestGateway restarted = start(seeded);
        HttpResponse<String> after = restarted.admin("GET", "/admin/routes/weather", TestTokens.admin("alice", "admin:routes"), null, Map.of());
        assertThat(TestGateway.json(after).path("spec").path("timeoutMs").asInt()).isEqualTo(2500);
    }

    @Test
    void r3_changesPropagateToEveryInstance() {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        seedBasics(a, 1000);
        awaitSameRevision(List.of(a, b), a.revision());
        assertThat(b.get("/svc/x", token()).statusCode()).isEqualTo(200);

        a.upsertRoute("svc", route("/svc", "{\"*\":[\"svc:write\"]}"));
        awaitSameRevision(List.of(a, b), a.revision());
        assertThat(b.get("/svc/x", token()).statusCode()).isEqualTo(403);

        a.admin("DELETE", "/admin/routes/svc", TestTokens.admin("alice", "admin:routes"), null, Map.of());
        awaitSameRevision(List.of(a, b), a.revision());
        assertThat(b.get("/svc/x", token()).statusCode()).isEqualTo(404);
    }

    @Test
    void r4_missedNotificationConvergesByPollAndListenReconnects() {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        seedBasics(a, 1000);
        awaitSameRevision(List.of(a, b), a.revision());
        long connectionsBefore = b.status().path("listenConnections").asLong();

        List<Map<String, Object>> killed = new Db(db).query(
                "SELECT pg_terminate_backend(pid) AS ok FROM pg_stat_activity WHERE application_name = 'waypoint-listen-b'");
        assertThat(killed).isNotEmpty();
        a.upsertRoute("other", route("/other", "{\"*\":[]}"));

        awaitSameRevision(List.of(a, b), a.revision());
        assertThat(b.get("/other/x", token()).statusCode()).isEqualTo(200);
        await().atMost(CONVERGE).until(() -> b.status().path("listenConnections").asLong() > connectionsBefore
                && b.status().path("listenConnected").asBoolean());
    }

    @Test
    void r5_concurrentWritesWithSameIfMatchLoseNoUpdate() {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        seedBasics(a, 1000);
        String etag = a.admin("GET", "/admin/routes/svc", TestTokens.admin("alice", "admin:routes"), null, Map.of())
                .headers().firstValue("ETag").orElseThrow();
        CompletableFuture<HttpResponse<String>> fromA = CompletableFuture.supplyAsync(() -> a.putRoute("svc", route("/svc", "{\"*\":[\"a:x\"]}"), etag));
        CompletableFuture<HttpResponse<String>> fromB = CompletableFuture.supplyAsync(() -> b.putRoute("svc", route("/svc", "{\"*\":[\"b:x\"]}"), etag));
        List<Integer> statuses = List.of(fromA.join().statusCode(), fromB.join().statusCode());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(new Db(db).query("SELECT count(*) AS n FROM config_audit WHERE entity_id = 'svc' AND action = 'update'").get(0))
                .containsEntry("n", 1L);
    }

    @Test
    void r13_badRowFailsClosedForItsPrefixOnlyAndDoesNotBlockStartup() {
        TestGateway a = start(node("a"));
        TestGateway b = start(node("b"));
        seedBasics(a, 1000);
        new Db(db).insertRawRoute("bad", "/bad", "{\"pathPrefix\":\"/bad\",\"upstreams\":[{\"url\":\"http://169.254.169.254\"}],\"requiredScopes\":{\"*\":[]}}");
        await().atMost(CONVERGE).until(() -> b.get("/bad/x", token()).statusCode() == 503);
        assertThat(a.get("/bad/x", token()).statusCode()).isEqualTo(503);
        assertThat(a.get("/svc/x", token()).statusCode()).isEqualTo(200);

        b.close();
        TestGateway b2 = start(node("b2"));
        assertThat(b2.isReady()).isTrue();
        assertThat(b2.get("/svc/x", token()).statusCode()).isEqualTo(200);
        assertThat(b2.get("/bad/x", token()).statusCode()).isEqualTo(503);
        assertThat(b2.status().path("invalidEntries").toString()).contains("route:bad");
    }

    @Test
    void r14_concurrentStartsOnEmptyDatabaseMigrateOnce() {
        List<CompletableFuture<TestGateway>> starting = List.of(node("a").startAsync(), node("b").startAsync(), node("c").startAsync());
        List<TestGateway> all = starting.stream().map(CompletableFuture::join).toList();
        nodes.addAll(all);
        all.forEach(TestGateway::awaitReady);
        assertThat(new Db(db).query("SELECT version, count(*) AS n FROM flyway_schema_history WHERE success GROUP BY version"))
                .allMatch(row -> row.get("n").equals(1L));
    }
}
