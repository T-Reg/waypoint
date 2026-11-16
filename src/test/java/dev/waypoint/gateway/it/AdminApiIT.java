package dev.waypoint.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import dev.waypoint.gateway.support.AuditCapture;
import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.Db;
import dev.waypoint.gateway.support.RawHttp;
import dev.waypoint.gateway.support.TestGateway;
import dev.waypoint.gateway.support.TestTokens;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdminApiIT {

    static TestGateway gw;
    static Db db;
    static final String ROUTES = TestTokens.admin("alice", "admin:routes");
    static final String TENANTS = TestTokens.admin("bob", "admin:tenants");
    static final String KEYS = TestTokens.admin("carol", "admin:keys");

    @BeforeAll
    static void start() {
        String url = Containers.newDatabase();
        db = new Db(url);
        gw = TestGateway.builder(url).name("admin-it").startReady();
    }

    @AfterAll
    static void stop() {
        gw.close();
    }

    static String route(String prefix) {
        return "{\"pathPrefix\":\"" + prefix + "\",\"upstreams\":[{\"url\":\"https://93.184.216.34\"}],\"requiredScopes\":{\"GET\":[\"x:read\"]}}";
    }

    static HttpResponse<String> call(String method, String path, String token, String body, Map<String, String> headers) {
        return gw.admin(method, path, token, body, headers);
    }

    static String code(HttpResponse<String> response) {
        return TestGateway.json(response).path("code").asString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/routes;x", "/admin/routes/a%41"})
    void pathParametersAndEncodedCharactersAre404(String rawPath) throws Exception {
        RawHttp.Response response = RawHttp.get(gw.adminPort(), rawPath, "Authorization: Bearer " + ROUTES);
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"admin.not_found\"");
    }

    @Test
    void customerTokensAreRejectedOnAdminListener() {
        HttpResponse<String> response = call("GET", "/admin/routes", TestTokens.customer("acme", "web", "admin:routes"), null, Map.of());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(call("GET", "/admin/routes", null, null, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void eachScopeGatesOnlyItsEndpoints() {
        assertThat(call("GET", "/admin/routes", ROUTES, null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/admin/tenants", TENANTS, null, Map.of()).statusCode()).isEqualTo(200);
        HttpResponse<String> denied = call("GET", "/admin/tenants", ROUTES, null, Map.of());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("admin.scope_missing");
        assertThat(call("PUT", "/admin/routes/x", TENANTS, route("/x"), Map.of()).statusCode()).isEqualTo(403);
        assertThat(call("DELETE", "/admin/tenants/x", KEYS, null, Map.of()).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/admin/keys/rotate", ROUTES, null, Map.of()).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/admin/routes/x", KEYS, null, Map.of()).statusCode()).isEqualTo(403);
    }

    @Test
    void crudWithOptimisticLockingAndNamedActor() {
        HttpResponse<String> created = call("PUT", "/admin/routes/crud", ROUTES, route("/crud"), Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("ETag")).contains("\"1\"");
        JsonNode body = TestGateway.json(created);
        assertThat(body.path("updatedBy").asString()).isEqualTo("alice");
        assertThat(body.path("spec").path("timeoutMs").asInt()).isEqualTo(10000);

        assertThat(code(call("PUT", "/admin/routes/crud", ROUTES, route("/crud"), Map.of()))).isEqualTo("admin.precondition_required");
        assertThat(call("PUT", "/admin/routes/crud", ROUTES, route("/crud"), Map.of("If-Match", "\"7\"")).statusCode()).isEqualTo(409);
        HttpResponse<String> updated = call("PUT", "/admin/routes/crud", ROUTES, route("/crud2"), Map.of("If-Match", "\"1\""));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.headers().firstValue("ETag")).contains("\"2\"");

        HttpResponse<String> fetched = call("GET", "/admin/routes/crud", ROUTES, null, Map.of());
        assertThat(TestGateway.json(fetched).path("spec").path("pathPrefix").asString()).isEqualTo("/crud2");
        assertThat(TestGateway.json(call("GET", "/admin/routes", ROUTES, null, Map.of())).findValuesAsString("id")).contains("crud");

        assertThat(call("DELETE", "/admin/routes/crud", ROUTES, null, Map.of("If-Match", "\"1\"")).statusCode()).isEqualTo(409);
        assertThat(call("DELETE", "/admin/routes/crud", ROUTES, null, Map.of("If-Match", "\"2\"")).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/admin/routes/crud", ROUTES, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("DELETE", "/admin/routes/crud", ROUTES, null, Map.of()).statusCode()).isEqualTo(404);

        List<Map<String, Object>> trail = db.query("SELECT actor, action, before IS NOT NULL AS had_before, after IS NOT NULL AS had_after "
                + "FROM config_audit WHERE entity_id = 'crud' ORDER BY id");
        assertThat(trail).extracting(r -> r.get("action")).containsExactly("create", "update", "delete");
        assertThat(trail).allMatch(r -> r.get("actor").equals("alice"));
        assertThat(trail.get(1)).containsEntry("had_before", true).containsEntry("had_after", true);
    }

    @Test
    void tenantCrudRecordsActor() {
        assertThat(call("PUT", "/admin/tenants/acme", TENANTS, "{\"allowedPathPrefixes\":[\"/x\"],\"requestsPerMinute\":5}", Map.of()).statusCode())
                .isEqualTo(201);
        assertThat(db.query("SELECT updated_by FROM tenants WHERE id = 'acme'").get(0)).containsEntry("updated_by", "bob");
        assertThat(call("PUT", "/admin/tenants/acme", TENANTS, "{\"allowedPathPrefixes\":[\"/x\"],\"requestsPerMinute\":0}",
                Map.of("If-Match", "\"1\"")).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/admin/tenants/acme", TENANTS, null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(call("DELETE", "/admin/tenants/acme", TENANTS, null, Map.of()).statusCode()).isEqualTo(204);
        assertThat(db.query("SELECT actor FROM config_audit WHERE entity_id = 'acme' AND action = 'delete'").get(0)).containsEntry("actor", "bob");
    }

    @Test
    void createWithIfMatchOnMissingEntryConflicts() {
        assertThat(call("PUT", "/admin/routes/ghost", ROUTES, route("/ghost"), Map.of("If-Match", "\"1\"")).statusCode()).isEqualTo(409);
    }

    @Test
    void duplicatePrefixConflicts() {
        assertThat(call("PUT", "/admin/routes/dup1", ROUTES, route("/dup"), Map.of()).statusCode()).isEqualTo(201);
        HttpResponse<String> clash = call("PUT", "/admin/routes/dup2", ROUTES, route("/dup"), Map.of());
        assertThat(clash.statusCode()).isEqualTo(409);
        assertThat(code(clash)).isEqualTo("admin.conflict");
    }

    @Test
    void rejectsInvalidInput() {
        assertThat(code(call("PUT", "/admin/routes/bad", ROUTES, "{\"pathPrefix\":\"/bad\",\"typo\":1}", Map.of()))).isEqualTo("admin.invalid");
        assertThat(call("PUT", "/admin/routes/bad", ROUTES, "{not json", Map.of()).statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/admin/routes/bad", ROUTES, route("/bad"), Map.of("If-Match", "W/\"1\"")).statusCode()).isEqualTo(400);
        assertThat(call("PUT", "/admin/routes/Bad!", ROUTES, route("/bad"), Map.of()).statusCode()).isEqualTo(400);
        assertThat(code(call("PUT", "/admin/routes/bad", ROUTES, "{\"pathPrefix\":\"/bad\",\"upstreams\":[],\"requiredScopes\":{}}", Map.of())))
                .isEqualTo("admin.invalid");
    }

    @Test
    void rejectsSsrfUpstreamsOnWrite() {
        for (String url : List.of("http://169.254.169.254/latest", "http://localhost:9999", "http://127.0.0.1:" + gw.adminPort(),
                "file:///etc/passwd", "http://user:pw@example.com")) {
            HttpResponse<String> response = call("PUT", "/admin/routes/ssrf", ROUTES,
                    "{\"pathPrefix\":\"/ssrf\",\"upstreams\":[{\"url\":\"" + url + "\"}],\"requiredScopes\":{\"*\":[]}}", Map.of());
            assertThat(response.statusCode()).as(url).isEqualTo(400);
        }
        HttpResponse<String> ownListener = call("PUT", "/admin/routes/ssrf", ROUTES,
                "{\"pathPrefix\":\"/ssrf\",\"upstreams\":[{\"url\":\"http://127.0.0.1:" + gw.managementPort()
                        + "\"}],\"requiredScopes\":{\"*\":[]},\"allowPrivateNetwork\":true}", Map.of());
        assertThat(ownListener.statusCode()).isEqualTo(400);
        assertThat(ownListener.body()).contains("own listeners");
    }

    @Test
    void unknownPathsAndMethods() {
        assertThat(call("GET", "/admin/nope", ROUTES, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/elsewhere", ROUTES, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/actuator/health", ROUTES, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("PATCH", "/admin/routes/x", ROUTES, "{}", Map.of()).statusCode()).isEqualTo(405);
    }

    @Test
    void everyAdminRequestIsAuditedIncludingFailures() {
        try (AuditCapture audit = new AuditCapture()) {
            String denied = call("GET", "/admin/routes", null, null, Map.of()).headers().firstValue("X-Request-Id").orElseThrow();
            String forbidden = call("GET", "/admin/tenants", ROUTES, null, Map.of()).headers().firstValue("X-Request-Id").orElseThrow();
            String ok = call("GET", "/admin/routes", ROUTES, null, Map.of()).headers().firstValue("X-Request-Id").orElseThrow();
            JsonNode deniedEntry = audit.forRequest(denied).orElseThrow();
            assertThat(deniedEntry.path("listener").asString()).isEqualTo("admin");
            assertThat(deniedEntry.path("decision").asString()).isEqualTo("DENY");
            assertThat(deniedEntry.path("deny_code").asString()).isEqualTo("admin.unauthorized");
            JsonNode forbiddenEntry = audit.forRequest(forbidden).orElseThrow();
            assertThat(forbiddenEntry.path("sub").asString()).isEqualTo("alice");
            assertThat(forbiddenEntry.path("deny_code").asString()).isEqualTo("admin.scope_missing");
            assertThat(audit.forRequest(ok).orElseThrow().path("decision").asString()).isEqualTo("ALLOW");
        }
    }
}
