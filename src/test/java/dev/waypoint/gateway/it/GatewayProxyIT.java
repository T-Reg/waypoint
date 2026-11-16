package dev.waypoint.gateway.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import tools.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jwt.JWTClaimsSet;
import dev.waypoint.gateway.store.ConfigSync;
import dev.waypoint.gateway.support.AuditCapture;
import dev.waypoint.gateway.support.Containers;
import dev.waypoint.gateway.support.Db;
import dev.waypoint.gateway.support.RawHttp;
import dev.waypoint.gateway.support.StallingUpstream;
import dev.waypoint.gateway.support.StallingUpstream.Then;
import dev.waypoint.gateway.support.TestGateway;
import dev.waypoint.gateway.support.TestTokens;
import dev.waypoint.gateway.support.UpstreamVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Full proxy path: client → gateway → WireMock upstream, including every rejection branch. */
class GatewayProxyIT {

    static WireMockServer upstream;
    static StallingUpstream stalling;
    static TestGateway gw;
    static Db db;
    static AuditCapture audit;
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeAll
    static void start() throws IOException {
        upstream = new WireMockServer(options().dynamicPort());
        upstream.start();
        stalling = StallingUpstream.start();
        String dbUrl = Containers.newDatabase();
        db = new Db(dbUrl);
        gw = TestGateway.builder(dbUrl).name("proxy-it").property("gateway.proxy.max-request-bytes", 1_000_000).startReady();
        audit = new AuditCapture();
        String up = "http://127.0.0.1:" + upstream.port();
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        gw.upsertTenant("acme", "{\"allowedPathPrefixes\":[\"/weather\",\"/maps\",\"/files\",\"/slow\",\"/stall\",\"/dead\",\"/third\",\"/open\","
                + "\"/admin\",\"/blocked\",\"/self\",\"/selfip\",\"/broken\",\"/nowhere\",\"/.well-known\"],\"requestsPerMinute\":10000}");
        gw.upsertTenant("globex", "{\"allowedPathPrefixes\":[\"/weather\"],\"requestsPerMinute\":10000}");
        gw.upsertTenant("initech", "{\"allowedPathPrefixes\":[\"/weather\"],\"requestsPerMinute\":10000,\"disabled\":true}");
        gw.upsertTenant("tiny", "{\"allowedPathPrefixes\":[\"/open\"],\"requestsPerMinute\":3}");
        gw.upsertTenant("chatty", "{\"allowedPathPrefixes\":[\"/weather\"],\"requestsPerMinute\":10000,\"auditRawPath\":true}");
        gw.upsertRoute("weather", route("/weather", up, "{\"GET\":[\"weather:read\"],\"POST\":[\"weather:write\"]}", ",\"audience\":\"weather-svc\""));
        gw.upsertRoute("maps", route("/maps", up, "{\"*\":[\"maps:read\"]}", ""));
        gw.upsertRoute("files", route("/files", up, "{\"*\":[]}", ",\"stripPrefix\":true"));
        gw.upsertRoute("slow", route("/slow", up, "{\"*\":[]}", ",\"timeoutMs\":300"));
        gw.upsertRoute("stall", route("/stall", "http://127.0.0.1:" + stalling.port(), "{\"*\":[]}", ",\"timeoutMs\":300"));
        gw.upsertRoute("dead", route("/dead", "http://127.0.0.1:" + closedPort, "{\"*\":[]}", ""));
        gw.upsertRoute("third", route("/third", up, "{\"*\":[]}", ",\"forwardIdentity\":false"));
        gw.upsertRoute("open", route("/open", up, "{\"*\":[]}", ""));
        db.insertRawRoute("blocked", "/blocked", "{\"pathPrefix\":\"/blocked\",\"upstreams\":[{\"url\":\"http://localhost:" + upstream.port()
                + "\"}],\"requiredScopes\":{\"*\":[]}}");
        db.insertRawRoute("self", "/self", "{\"pathPrefix\":\"/self\",\"upstreams\":[{\"url\":\"http://localhost:" + gw.adminPort()
                + "\"}],\"requiredScopes\":{\"*\":[]},\"allowPrivateNetwork\":true}");
        db.insertRawRoute("selfip", "/selfip", "{\"pathPrefix\":\"/selfip\",\"upstreams\":[{\"url\":\"http://127.0.0.1:" + gw.adminPort()
                + "\"}],\"requiredScopes\":{\"*\":[]},\"allowPrivateNetwork\":true}");
        db.insertRawRoute("broken", "/broken", "{\"pathPrefix\":\"/broken\",\"upstreams\":[{\"url\":\"ftp://nope\"}],\"requiredScopes\":{\"*\":[]}}");
        gw.bean(ConfigSync.class).refresh();
    }

    static String route(String prefix, String url, String scopes, String extra) {
        return "{\"pathPrefix\":\"" + prefix + "\",\"upstreams\":[{\"url\":\"" + url + "\"}],\"requiredScopes\":" + scopes
                + ",\"allowPrivateNetwork\":true" + extra + "}";
    }

    @AfterAll
    static void stop() throws IOException {
        audit.close();
        gw.close();
        upstream.stop();
        stalling.close();
    }

    @BeforeEach
    void reset() {
        upstream.resetAll();
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("X-Upstream", "yes").withBody("hello")));
        audit.clear();
    }

    static String acme(String scope) {
        return TestTokens.customer("acme", "web-app", scope);
    }

    static List<LoggedRequest> received() {
        return upstream.findAll(anyRequestedFor(anyUrl()));
    }

    static void assertNoUpstreamCall() {
        assertThat(received()).as("upstream must not be called").isEmpty();
    }

    static JsonNode problem(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        return TestGateway.json(response);
    }

    // --- happy path and faithful forwarding ---

    @Test
    void forwardsAllowedRequestFaithfully() throws Exception {
        upstream.stubFor(any(urlPathMatching("/weather/.*")).willReturn(aResponse().withStatus(201)
                .withHeader("X-Upstream", "yes").withHeader("Set-Cookie", "a=1").withBody("created")));
        HttpResponse<String> response = gw.get("/weather//today/./san%20jos%C3%A9?units=metric&x=%2F", acme("weather:read billing:write"));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("created");
        assertThat(response.headers().firstValue("X-Upstream")).contains("yes");
        assertThat(response.headers().firstValue("Set-Cookie")).contains("a=1");
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(response.headers().firstValue("X-Gateway-Instance")).as("off unless gateway.proxy.expose-instance-header").isEmpty();

        LoggedRequest got = received().get(0);
        assertThat(got.getUrl()).isEqualTo("/weather/today/san%20jos%C3%A9?units=metric&x=%2F");
        assertThat(got.containsHeader("Authorization")).isFalse();
        assertThat(got.getHeader("X-Request-Id")).isEqualTo(requestId);
        assertThat(got.getHeader("X-Forwarded-For")).isEqualTo("127.0.0.1");
        assertThat(got.getHeader("X-Forwarded-Proto")).isEqualTo("http");
        assertThat(got.getHeader("X-Forwarded-Host")).isEqualTo("127.0.0.1:" + gw.publicPort());

        JWTClaimsSet identity = UpstreamVerifier.remote(gw.managementPort(), "weather-svc").verify(got.getHeader("X-Gateway-Identity"));
        assertThat(identity.getClaim("tid")).isEqualTo("acme");
        assertThat(identity.getSubject()).isEqualTo("web-app");
        assertThat(identity.getClaim("scope")).as("unrelated scopes are filtered out").isEqualTo("weather:read");
        assertThat(identity.getClaim("rid")).isEqualTo(requestId);
        assertThat(Duration.between(identity.getIssueTime().toInstant(), identity.getExpirationTime().toInstant()))
                .isLessThanOrEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void defaultAudienceIsRouteIdAndForwardScopesDefaultToNamespaces() throws Exception {
        gw.get("/maps/x", acme("maps:read maps:write weather:read"));
        JWTClaimsSet identity = UpstreamVerifier.remote(gw.managementPort(), "maps").verify(received().get(0).getHeader("X-Gateway-Identity"));
        assertThat(identity.getClaim("scope")).isEqualTo("maps:read maps:write");
    }

    @Test
    void identityTokenIsAudienceBound() {
        gw.get("/maps/x", acme("maps:read"));
        String token = received().get(0).getHeader("X-Gateway-Identity");
        assertThat(UpstreamVerifier.remote(gw.managementPort(), "maps").accepts(token)).isTrue();
        assertThat(UpstreamVerifier.remote(gw.managementPort(), "weather-svc").accepts(token)).isFalse();
    }

    @Test
    void forwardIdentityFalseSendsNoIdentity() {
        assertThat(gw.get("/third/x", acme("")).statusCode()).isEqualTo(200);
        assertThat(received().get(0).containsHeader("X-Gateway-Identity")).isFalse();
    }

    @Test
    void stripsPrefixAndRoundTripsOpaqueBinaryBody() throws Exception {
        byte[] body = new byte[200_000];
        new Random(42).nextBytes(body);
        upstream.stubFor(any(urlPathMatching("/upload")).willReturn(aResponse().withStatus(200).withBody(body)));
        HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gw.publicPort() + "/files/upload"))
                .header("Authorization", "Bearer " + acme(""))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
        LoggedRequest got = received().get(0);
        assertThat(got.getUrl()).isEqualTo("/upload");
        assertThat(got.getBody()).isEqualTo(body);
        assertThat(got.getHeader("Content-Length")).isEqualTo("200000");
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(audit.lines()).noneMatch(line -> line.contains(java.util.Base64.getEncoder().encodeToString(body).substring(0, 40)));
        assertThat(audit.forRequest(requestId)).isPresent();
    }

    @Test
    void streamsLargeResponses() {
        String big = "x".repeat(3_000_000);
        upstream.stubFor(any(urlPathMatching("/open/big")).willReturn(aResponse().withStatus(200).withBody(big)));
        HttpResponse<String> response = gw.get("/open/big", acme(""));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(3_000_000);
    }

    @Test
    void streamsChunkedRequestBodies() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gw.publicPort() + "/open/chunked"))
                .header("Authorization", "Bearer " + acme(""))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream("streamed".getBytes()))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(received().get(0).getBodyAsString()).isEqualTo("streamed");
    }

    @Test
    void headIsForwarded() {
        assertThat(gw.call("HEAD", "/open/x", acme(""), null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(received().get(0).getMethod().value()).isEqualTo("HEAD");
    }

    // --- header hygiene ---

    @Test
    void spoofedIdentityAndForwardingHeadersAreReplaced() throws IOException {
        RawHttp.Response response = RawHttp.get(gw.publicPort(), "/open/x",
                "Authorization: Bearer " + acme(""),
                "X-Gateway-Identity: forged",
                "X-Gateway-Tenant-Id: globex",
                "X-Forwarded-For: 6.6.6.6",
                "X-Forwarded-Proto: https",
                "Forwarded: for=6.6.6.6",
                "X-Real-IP: 6.6.6.6",
                "X-Request-Id: client-chosen",
                "Keep-Alive: timeout=5",
                "X-Hop: secret",
                "Connection: X-Gateway-Identity, X-Request-Id, X-Hop");
        assertThat(response.status()).isEqualTo(200);
        LoggedRequest got = received().get(0);
        assertThat(got.getHeader("X-Gateway-Identity")).isNotEqualTo("forged");
        assertThat(UpstreamVerifier.remote(gw.managementPort(), "open").accepts(got.getHeader("X-Gateway-Identity"))).isTrue();
        assertThat(got.getHeaders().getHeader("X-Gateway-Identity").values()).hasSize(1);
        assertThat(got.containsHeader("X-Gateway-Tenant-Id")).isFalse();
        assertThat(got.getHeader("X-Request-Id")).isNotEqualTo("client-chosen");
        assertThat(got.getHeader("X-Forwarded-For")).isEqualTo("127.0.0.1");
        assertThat(got.getHeader("X-Forwarded-Proto")).isEqualTo("http");
        assertThat(got.containsHeader("Forwarded")).isFalse();
        assertThat(got.containsHeader("X-Real-IP")).isFalse();
        assertThat(got.containsHeader("Keep-Alive")).isFalse();
        assertThat(got.containsHeader("X-Hop")).isFalse();
    }

    // --- upstream failures ---

    @Test
    void upstreamTimeoutIs504() {
        upstream.stubFor(any(urlPathMatching("/slow.*")).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
        HttpResponse<String> response = gw.get("/slow/x", acme(""));
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(problem(response).path("code").asString()).isEqualTo("upstream.timeout");
    }

    @Test
    void steadyBodyLongerThanTheTimeoutIsNotCutOff() {
        String body = "0123456789".repeat(6);
        upstream.stubFor(any(urlPathMatching("/slow/steady")).willReturn(aResponse().withStatus(200).withBody(body)
                .withChunkedDribbleDelay(6, 600)));
        HttpResponse<String> response = gw.get("/slow/steady", acme(""));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
    }

    @Test
    void bodyStallBeforeAnyByteIs504WithoutUpstreamHeaders() {
        stalling.respondChunked(0, Then.STALL);
        HttpResponse<String> response = gw.get("/stall/x", acme(""));
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(problem(response).path("code").asString()).isEqualTo("upstream.timeout");
        assertThat(response.headers().firstValue("X-Upstream")).isEmpty();
        assertThat(errorCode(response.headers().firstValue("X-Request-Id").orElseThrow())).isEqualTo("upstream.timeout");
    }

    @Test
    void bodyStallAfterCommitAbortsTheConnection() throws IOException {
        stalling.respondChunked(64 * 1024, Then.STALL);
        RawHttp.Response response = RawHttp.get(gw.publicPort(), "/stall/x", "Authorization: Bearer " + acme(""));
        assertAbortedMidBody(response, 64 * 1024);
        assertThat(errorCode(requestId(response))).isEqualTo("upstream.timeout");
    }

    @Test
    void upstreamDropBeforeAnyByteIs502() {
        stalling.respondChunked(0, Then.DROP);
        HttpResponse<String> response = gw.get("/stall/x", acme(""));
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(problem(response).path("code").asString()).isEqualTo("upstream.unavailable");
        assertThat(response.headers().firstValue("X-Upstream")).isEmpty();
    }

    @Test
    void upstreamDropAfterCommitAbortsTheConnection() throws IOException {
        stalling.respondChunked(64 * 1024, Then.DROP);
        RawHttp.Response response = RawHttp.get(gw.publicPort(), "/stall/x", "Authorization: Bearer " + acme(""));
        assertAbortedMidBody(response, 64 * 1024);
        assertThat(errorCode(requestId(response))).isEqualTo("upstream.stream_interrupted");
    }

    @Test
    void clientDisconnectMidBodyIsAuditedAsStreamInterrupted() throws IOException {
        stalling.respondChunked(16 * 1024 * 1024, Then.STALL);
        String requestId;
        try (Socket client = new Socket("127.0.0.1", gw.publicPort())) {
            client.getOutputStream().write(("GET /stall/x HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + acme("") + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            requestId = requestId(readHead(client.getInputStream()));
            client.setSoLinger(true, 0);
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> audit.forRequest(requestId).isPresent());
        assertThat(errorCode(requestId)).as("a write failure, not the upstream idle timeout").isEqualTo("upstream.stream_interrupted");
    }

    static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        for (int b; !head.toString().endsWith("\r\n\r\n") && (b = in.read()) != -1; ) {
            head.append((char) b);
        }
        return head.toString();
    }

    static void assertAbortedMidBody(RawHttp.Response response, int relayedBytes) {
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.head()).containsIgnoringCase("Transfer-Encoding: chunked");
        assertThat(response.body().chars().filter(c -> c == 'x').count()).as("bytes relayed before the failure").isEqualTo(relayedBytes);
        assertThat(response.body()).as("no terminating chunk, so the client can't mistake it for complete").doesNotEndWith("0\r\n\r\n");
        assertThat(response.body()).as("no error page appended to the body").doesNotContain("{");
    }

    static String requestId(RawHttp.Response response) {
        return requestId(response.head());
    }

    static String requestId(String head) {
        return head.lines().filter(l -> l.regionMatches(true, 0, "X-Request-Id:", 0, 13))
                .map(l -> l.substring(13).trim()).findFirst().orElseThrow();
    }

    static String errorCode(String requestId) {
        return audit.forRequest(requestId).orElseThrow().path("error_code").asString();
    }

    @Test
    void connectFailureIs502() {
        HttpResponse<String> response = gw.get("/dead/x", acme(""));
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(problem(response).path("code").asString()).isEqualTo("upstream.unavailable");
    }

    @Test
    void ssrfBlockedAtRequestTimeEvenIfStoredRowBypassedValidation() {
        HttpResponse<String> response = gw.get("/blocked/x", acme(""));
        assertThat(response.statusCode()).isEqualTo(502);
        assertNoUpstreamCall();
    }

    @Test
    void neverProxiesToItsOwnListeners() {
        assertThat(gw.get("/self/admin/routes", acme("")).statusCode()).as("hostname: blocked at connect time").isEqualTo(502);
        assertThat(gw.get("/selfip/admin/routes", acme("")).statusCode()).as("IP literal: invalid at load, fails closed").isEqualTo(503);
    }

    @Test
    void upstreamErrorsArePassedThroughFaithfully() {
        upstream.stubFor(any(urlPathMatching("/open/err")).willReturn(aResponse().withStatus(503).withBody("upstream says no")));
        HttpResponse<String> response = gw.get("/open/err", acme(""));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("upstream says no");
    }

    // --- rejections: none may reach the upstream ---

    @Test
    void missingToken() {
        HttpResponse<String> response = gw.get("/weather", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(problem(response).path("code").asString()).isEqualTo("auth.missing");
        assertNoUpstreamCall();
    }

    @Test
    void invalidExpiredAndWrongTypeTokens() {
        assertThat(problem(gw.get("/weather", "garbage")).path("code").asString()).isEqualTo("auth.invalid");
        String expired = TestTokens.customer().tenant("acme").scope("weather:read")
                .clock(java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofHours(-1))).sign();
        assertThat(problem(gw.get("/weather", expired)).path("code").asString()).isEqualTo("auth.expired");
        assertThat(problem(gw.get("/weather", TestTokens.admin("alice", "admin:routes"))).path("code").asString()).isEqualTo("auth.invalid");
        assertNoUpstreamCall();
    }

    @Test
    void unknownAndDisabledTenants() {
        assertThat(problem(gw.get("/weather", TestTokens.customer("hooli", "x", "weather:read"))).path("code").asString()).isEqualTo("auth.unknown_tenant");
        assertThat(problem(gw.get("/weather", TestTokens.customer("initech", "x", "weather:read"))).path("code").asString()).isEqualTo("auth.unknown_tenant");
        assertNoUpstreamCall();
    }

    @Test
    void pathOutsideAllowlistIs403RegardlessOfRouteExistence() {
        HttpResponse<String> existing = gw.get("/maps/x", TestTokens.customer("globex", "x", "maps:read"));
        HttpResponse<String> missing = gw.get("/secret", TestTokens.customer("globex", "x", ""));
        assertThat(existing.statusCode()).isEqualTo(403);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(problem(existing).path("code").asString()).isEqualTo("policy.path_denied");
        assertNoUpstreamCall();
    }

    @Test
    void routeNotFoundOnlyInsideAllowlist() {
        HttpResponse<String> response = gw.get("/nowhere", acme(""));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(problem(response).path("code").asString()).isEqualTo("route.not_found");
        assertNoUpstreamCall();
    }

    @Test
    void methodNotConfiguredIs405WithAllow() {
        HttpResponse<String> response = gw.call("DELETE", "/weather", acme("weather:read weather:write"), null, Map.of());
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).contains("GET, POST");
        assertThat(problem(response).path("code").asString()).isEqualTo("policy.method_denied");
        assertNoUpstreamCall();
    }

    @Test
    void missingScopeForMethod() {
        HttpResponse<String> response = gw.call("POST", "/weather", acme("weather:read"), "{}", Map.of());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(problem(response).path("code").asString()).isEqualTo("policy.scope_missing");
        assertThat(gw.get("/maps", acme("weather:read")).statusCode()).as("* fallback").isEqualTo(403);
        assertNoUpstreamCall();
    }

    @Test
    void quotaExceededIs429WithRetryAfter() {
        String token = TestTokens.customer("tiny", "x", "");
        for (int i = 0; i < 3; i++) {
            assertThat(gw.get("/open/q", token).statusCode()).isEqualTo(200);
        }
        upstream.resetRequests();
        HttpResponse<String> response = gw.get("/open/q", token);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 60);
        assertThat(problem(response).path("code").asString()).isEqualTo("policy.quota_exceeded");
        assertNoUpstreamCall();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/weather/..;/admin", "/weather/%2e%2e/maps", "/weather%2Fx", "/weather/%252e%252e/x", "/weather/a%5Cb",
            "/weather;jsessionid=1", "/weather/%00"})
    void ambiguousPathsAre400(String rawPath) throws IOException {
        RawHttp.Response response = RawHttp.get(gw.publicPort(), rawPath, "Authorization: Bearer " + acme("weather:read"));
        assertThat(response.status()).isEqualTo(400);
        assertNoUpstreamCall();
    }

    @Test
    void queryTheUpstreamUriCannotCarryIs400() throws IOException {
        RawHttp.Response response = RawHttp.get(gw.publicPort(), "/weather/x?a=%zz", "Authorization: Bearer " + acme("weather:read"));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"request.bad_path\"");
        assertThat(errorCode(requestId(response))).isEqualTo("request.bad_path");
        assertNoUpstreamCall();
    }

    @Test
    void oversizedBodyIs413() {
        HttpResponse<String> response = gw.call("POST", "/files/x", acme(""), "x".repeat(1_000_001), Map.of());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(problem(response).path("code").asString()).isEqualTo("request.too_large");
        assertNoUpstreamCall();
    }

    @Test
    void oversizedChunkedBodyIs413() throws Exception {
        byte[] big = new byte[1_200_000];
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gw.publicPort() + "/files/x"))
                .header("Authorization", "Bearer " + acme(""))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void invalidRouteEntryFailsClosedWith503() {
        HttpResponse<String> response = gw.get("/broken/x", acme(""));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(problem(response).path("code").asString()).isEqualTo("route.unavailable");
        assertNoUpstreamCall();
    }

    @Test
    void adminApiAndJwksAreNotReachableOnThePublicListener() {
        assertThat(gw.call("POST", "/admin/keys/rotate", TestTokens.admin("alice", "admin:keys"), null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(gw.call("POST", "/admin/keys/rotate", acme(""), null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(gw.get("/.well-known/jwks.json", acme("")).statusCode()).isEqualTo(404);
        assertNoUpstreamCall();
    }

    // --- audit ---

    @Test
    void auditMinimizesPathByDefault() {
        HttpResponse<String> response = gw.get("/weather/cities/chicago?secret=q", acme("weather:read"));
        JsonNode entry = audit.forRequest(response.headers().firstValue("X-Request-Id").orElseThrow()).orElseThrow();
        assertThat(entry.path("tenant_id").asString()).isEqualTo("acme");
        assertThat(entry.path("path_prefix").asString()).isEqualTo("/weather");
        assertThat(entry.path("path_hmac").asString()).isNotBlank();
        assertThat(entry.has("path")).isFalse();
        assertThat(entry.path("route_id").asString()).isEqualTo("weather");
        assertThat(entry.path("upstream").asString()).isEqualTo("http://127.0.0.1:" + upstream.port());
        assertThat(entry.path("decision").asString()).isEqualTo("ALLOW");
        assertThat(entry.path("upstream_status").asInt()).isEqualTo(200);
        assertThat(entry.path("latency_ms").isNumber()).isTrue();
        assertThat(entry.path("client_ip").asString()).isEqualTo("127.0.0.1");
        assertThat(audit.lines()).noneMatch(l -> l.contains("chicago") || l.contains("secret=q") || l.contains("eyJ"));
    }

    @Test
    void rawPathOnlyForOptedInTenant() {
        HttpResponse<String> response = gw.get("/weather/cities/chicago", TestTokens.customer("chatty", "x", "weather:read"));
        JsonNode entry = audit.forRequest(response.headers().firstValue("X-Request-Id").orElseThrow()).orElseThrow();
        assertThat(entry.path("path").asString()).isEqualTo("/weather/cities/chicago");
    }

    @Test
    void hmacIsStableForTheSamePath() {
        String a = gw.get("/weather/same", acme("weather:read")).headers().firstValue("X-Request-Id").orElseThrow();
        String b = gw.get("/weather//same/", acme("weather:read")).headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(audit.forRequest(a).orElseThrow().path("path_hmac")).isEqualTo(audit.forRequest(b).orElseThrow().path("path_hmac"));
    }

    @Test
    void everyRejectionIsAuditedWithItsCode() {
        String id = gw.get("/weather", null).headers().firstValue("X-Request-Id").orElseThrow();
        JsonNode entry = audit.forRequest(id).orElseThrow();
        assertThat(entry.path("decision").asString()).isEqualTo("DENY");
        assertThat(entry.path("deny_code").asString()).isEqualTo("auth.missing");
        assertThat(entry.path("tenant_id").isNull()).isTrue();
        assertThat(entry.path("status").asInt()).isEqualTo(401);
        String timeout = gw.get("/dead/x", acme("")).headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(audit.forRequest(timeout).orElseThrow().path("error_code").asString()).isEqualTo("upstream.unavailable");
    }
}
