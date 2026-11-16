package dev.waypoint.gateway.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.TestGateway;
import dev.waypoint.gateway.support.TestTokens;
import dev.waypoint.gateway.support.UpstreamVerifier;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** JWKS endpoint, identity verification as an upstream would do it, and rotation end to end. */
class IdentityIT {

    static WireMockServer upstream;
    static TestGateway gw;

    @BeforeAll
    static void start() {
        upstream = new WireMockServer(options().dynamicPort());
        upstream.start();
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        gw = TestGateway.builder(Containers.newDatabase()).name("identity-it").startReady();
        String up = "http://127.0.0.1:" + upstream.port();
        gw.upsertTenant("acme", "{\"allowedPathPrefixes\":[\"/a\",\"/b\"],\"requestsPerMinute\":10000}");
        gw.upsertRoute("a", "{\"pathPrefix\":\"/a\",\"upstreams\":[{\"url\":\"" + up + "\"}],\"requiredScopes\":{\"*\":[]},\"allowPrivateNetwork\":true,\"audience\":\"svc-a\"}");
        gw.upsertRoute("b", "{\"pathPrefix\":\"/b\",\"upstreams\":[{\"url\":\"" + up + "\"}],\"requiredScopes\":{\"*\":[]},\"allowPrivateNetwork\":true,\"audience\":\"svc-b\"}");
    }

    @AfterAll
    static void stop() {
        gw.close();
        upstream.stop();
    }

    static String captureIdentity(String path) {
        upstream.resetRequests();
        assertThat(gw.get(path, TestTokens.customer("acme", "web", "")).statusCode()).isEqualTo(200);
        return upstream.findAll(anyRequestedFor(anyUrl())).get(0).getHeader("X-Gateway-Identity");
    }

    @Test
    void jwksServedOnManagementListenerOnly() {
        HttpResponse<String> response = TestGateway.send(gw.managementPort(), "GET", "/.well-known/jwks.json", null, null, Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/jwk-set+json");
        assertThat(response.headers().firstValue("Cache-Control")).contains("max-age=300");
        JsonNode keys = TestGateway.json(response).path("keys");
        assertThat(keys).isNotEmpty();
        keys.forEach(k -> assertThat(k.has("d")).isFalse());
        assertThat(TestGateway.send(gw.adminPort(), "GET", "/.well-known/jwks.json", null, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(TestGateway.send(gw.publicPort(), "GET", "/.well-known/jwks.json", null, null, Map.of()).statusCode())
                .as("public listener never serves it; unauthenticated callers get 401 first").isEqualTo(401);
    }

    @Test
    void managementListenerExposesNothingElse() {
        assertThat(TestGateway.send(gw.managementPort(), "GET", "/actuator/env", null, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(TestGateway.send(gw.managementPort(), "GET", "/admin/routes", TestTokens.admin("alice", "admin:routes"), null, Map.of()).statusCode())
                .isEqualTo(404);
        assertThat(TestGateway.send(gw.managementPort(), "GET", "/actuator/health/liveness", null, null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(gw.status().path("ready").asBoolean()).isTrue();
    }

    @Test
    void tokenVerifiesAgainstRunningGatewaysJwksAndIsAudienceBound() throws Exception {
        String token = captureIdentity("/a/x");
        assertThat(UpstreamVerifier.remote(gw.managementPort(), "svc-a").verify(token).getClaim("tid")).isEqualTo("acme");
        assertThat(UpstreamVerifier.remote(gw.managementPort(), "svc-b").accepts(token)).as("replay to B").isFalse();
        assertThat(UpstreamVerifier.remote(gw.managementPort(), "svc-b").accepts(captureIdentity("/b/x"))).isTrue();
    }

    @Test
    void rotationEndToEnd() throws Exception {
        UpstreamVerifier cached = UpstreamVerifier.remote(gw.managementPort(), "svc-a");
        String before = captureIdentity("/a/x");
        assertThat(cached.accepts(before)).isTrue();
        String oldKid = SignedJWT.parse(before).getHeader().getKeyID();

        HttpResponse<String> rotated = gw.rotateKeys();
        assertThat(rotated.statusCode()).isEqualTo(200);
        String newKid = TestGateway.json(rotated).path("kid").asString();
        assertThat(newKid).isNotEqualTo(oldKid);

        String after = captureIdentity("/a/x");
        assertThat(SignedJWT.parse(after).getHeader().getKeyID()).isEqualTo(newKid);
        List<String> published = gw.jwks().path("keys").findValuesAsString("kid");
        assertThat(published).contains(oldKid, newKid);
        assertThat(cached.accepts(before)).as("old token valid during overlap").isTrue();
        assertThat(cached.accepts(after)).as("cached verifier refetches on unknown kid").isTrue();
        assertThat(gw.status().path("keyGeneration").asLong()).isPositive();
    }

    @Test
    void rotateRequiresAdminKeysScopeOnAdminListener() {
        assertThat(gw.admin("POST", "/admin/keys/rotate", null, null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(gw.admin("POST", "/admin/keys/rotate", TestTokens.customer("acme", "web", "admin:keys"), null, Map.of()).statusCode()).isEqualTo(401);
        HttpResponse<String> wrongScope = gw.admin("POST", "/admin/keys/rotate", TestTokens.admin("alice", "admin:routes"), null, Map.of());
        assertThat(wrongScope.statusCode()).isEqualTo(403);
        assertThat(TestGateway.json(wrongScope).path("code").asString()).isEqualTo("admin.scope_missing");
        assertThat(gw.call("POST", "/admin/keys/rotate", TestTokens.admin("alice", "admin:keys"), null, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectedRequestsMintNothing() {
        upstream.resetRequests();
        assertThat(gw.get("/a/x", null).statusCode()).isEqualTo(401);
        assertThat(gw.get("/c/x", TestTokens.customer("acme", "web", "")).statusCode()).isEqualTo(403);
        assertThat(upstream.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }
}
