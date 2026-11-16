package dev.waypoint.visualizer.traffic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jwt.SignedJWT;
import dev.waypoint.visualizer.config.VisualizerConfig;
import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.support.TestSupport;
import dev.waypoint.visualizer.token.TokenFault;
import dev.waypoint.visualizer.token.TokenMinter;
import dev.waypoint.visualizer.traffic.RequestResult.Outcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The caller against a WireMock "gateway" and a real WireMock upstream journal. */
class GatewayCallerIT {

    private static final String PROBLEM = "application/problem+json";

    private final HttpClient http = new VisualizerConfig().httpClient();
    private WireMockServer gateway;
    private WireMockServer upstream;
    private GatewayCaller caller;

    @BeforeEach
    void start() {
        gateway = new WireMockServer(options().dynamicPort());
        upstream = new WireMockServer(options().dynamicPort());
        gateway.start();
        upstream.start();
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        gateway.stubFor(get("/.well-known/jwks.json").willReturn(aResponse().withBody(TestSupport.jwks(TestSupport.GATEWAY_KEY))));
        caller = caller(gateway.baseUrl(), upstream.baseUrl() + "/__admin");
    }

    @AfterEach
    void stop() {
        gateway.stop();
        upstream.stop();
    }

    private GatewayCaller caller(String gatewayUrl, String upstreamAdmin) {
        VisualizerProperties properties = TestSupport.properties(gatewayUrl, upstreamAdmin, gateway.baseUrl() + "/.well-known/jwks.json");
        JsonMapper mapper = JsonMapper.builder().build();
        IdentityVerifier identities = new IdentityVerifier(http, properties, TestSupport.fixedClock());
        return new GatewayCaller(http, mapper, new TokenMinter(properties, TestSupport.fixedClock()),
                new UpstreamJournal(http, mapper, properties, identities), properties, TestSupport.fixedClock());
    }

    private static RequestSpec acme(String target, String method, String path, TokenFault fault) {
        return new RequestSpec(target, method, path, "acme", "web-app", "weather:read", fault, Map.of(), null);
    }

    /** Simulates the gateway forwarding a request to the upstream with its injected headers. */
    private void upstreamReceives(String requestId, String identityToken) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(upstream.baseUrl() + "/weather/today"))
                .header("X-Request-Id", requestId)
                .header("X-Gateway-Identity", identityToken)
                .header("X-Forwarded-For", "10.0.0.7")
                .build(), HttpResponse.BodyHandlers.discarding());
    }

    private void gatewayAnswers(int status, Map<String, String> headers, String body) {
        var response = aResponse().withStatus(status).withBody(body);
        headers.forEach(response::withHeader);
        gateway.stubFor(any(urlPathEqualTo("/weather/today")).willReturn(response));
    }

    private static String problem(int status, String code) {
        return "{\"type\":\"about:blank\",\"status\":" + status + ",\"detail\":\"nope\",\"code\":\"" + code + "\",\"request_id\":\"r\"}";
    }

    @Test
    void allowedRequestShowsWhatTheUpstreamReceivedAndVerifiesTheIdentity() throws Exception {
        upstreamReceives("req-1", TestSupport.identityToken(TestSupport.GATEWAY_KEY, "acme"));
        gatewayAnswers(200, Map.of("X-Request-Id", "req-1", "X-Served-By", "gateway-2", "Content-Type", "application/json"),
                "{\"upstream\":\"ok\"}");

        RequestResult result = caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), "run-1", "happy-path");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(result.stage()).isEqualTo(Stage.UPSTREAM);
        assertThat(result.servedBy()).isEqualTo("gateway-2");
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(result.scenarioId()).isEqualTo("happy-path");
        assertThat(result.responseBody()).isEqualTo("{\"upstream\":\"ok\"}");
        assertThat(result.upstream().received()).isTrue();
        assertThat(result.upstream().url()).isEqualTo("/weather/today");
        assertThat(result.upstream().headers()).containsEntry("X-Forwarded-For", "10.0.0.7");
        assertThat(result.upstream().headers().get("X-Gateway-Identity")).endsWith(".<signature redacted>");
        assertThat(result.upstream().identity().verified()).isTrue();
        assertThat(result.upstream().identity().kid()).isEqualTo("gateway-test");
        assertThat(result.upstream().identity().claims()).containsEntry("tid", "acme");

        LoggedRequest sent = gateway.findAll(anyRequestedFor(urlEqualTo("/weather/today"))).getFirst();
        SignedJWT token = SignedJWT.parse(sent.getHeader("Authorization").substring("Bearer ".length()));
        assertThat(token.verify(new Ed25519Verifier(TestSupport.ISSUER_KEY.toPublicJWK()))).isTrue();
    }

    @Test
    void gatewayRejectionNeverQueriesTheJournal() {
        gatewayAnswers(403, Map.of("Content-Type", PROBLEM, "X-Request-Id", "req-2", "X-Served-By", "gateway-1"),
                problem(403, "policy.path_denied"));

        RequestResult result = caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.stage()).isEqualTo(Stage.ALLOWLIST);
        assertThat(result.code()).isEqualTo("policy.path_denied");
        assertThat(result.detail()).isEqualTo("nope");
        assertThat(result.upstream().checked()).isTrue();
        assertThat(result.upstream().received()).isFalse();
        upstream.verify(0, postRequestedFor(urlEqualTo("/__admin/requests/find")));
    }

    @Test
    void quotaAndMethodRejectionsCarryTheirHeaders() {
        gatewayAnswers(429, Map.of("Content-Type", PROBLEM, "Retry-After", "12"), problem(429, "policy.quota_exceeded"));
        RequestResult quota = caller.send(acme("gateway-1", "GET", "/weather/today", TokenFault.NONE), null, null);

        assertThat(quota.stage()).isEqualTo(Stage.QUOTA);
        assertThat(quota.retryAfter()).isEqualTo("12");
        assertThat(quota.servedBy()).isEqualTo("gateway-1");

        gatewayAnswers(405, Map.of("Content-Type", PROBLEM, "Allow", "GET, POST"), problem(405, "policy.method_denied"));
        RequestResult method = caller.send(acme("lb", "DELETE", "/weather/today", TokenFault.NONE), null, null);

        assertThat(method.stage()).isEqualTo(Stage.METHOD);
        assertThat(method.allow()).isEqualTo("GET, POST");
        assertThat(method.servedBy()).isNull();
    }

    @Test
    void requestTheUpstreamNeverSawWithoutAGatewayCodeIsALoadBalancerError() {
        gatewayAnswers(503, Map.of("Content-Type", "text/html", "X-Request-Id", "req-missing"), "<html>No server is available</html>");

        RequestResult result = caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null);

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.stage()).isEqualTo(Stage.LOAD_BALANCER);
        assertThat(result.code()).isNull();
        assertThat(result.upstream().received()).isFalse();
    }

    @Test
    void malformedProblemBodyIsTreatedAsHavingNoCode() {
        gatewayAnswers(400, Map.of("Content-Type", PROBLEM), "{not json");

        RequestResult result = caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null);

        assertThat(result.code()).isNull();
        assertThat(result.stage()).isEqualTo(Stage.LISTENER);
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void unreachableGatewayIsReportedAtTheFirstHop() {
        GatewayCaller offline = caller("http://127.0.0.1:1", upstream.baseUrl() + "/__admin");

        RequestResult result = offline.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null);

        assertThat(result.status()).isZero();
        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.stage()).isEqualTo(Stage.LOAD_BALANCER);
        assertThat(result.detail()).startsWith("lb unreachable");
        assertThat(result.upstream().checked()).isFalse();
    }

    @Test
    void unavailableJournalStillPlacesSuccessfulResponsesAtTheUpstream() {
        GatewayCaller blind = caller(gateway.baseUrl(), "http://127.0.0.1:1/__admin");
        gatewayAnswers(200, Map.of("X-Request-Id", "req-3"), "ok");

        RequestResult result = blind.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null);

        assertThat(result.stage()).isEqualTo(Stage.UPSTREAM);
        assertThat(result.upstream().checked()).isFalse();
    }

    @Test
    void journalErrorStatusAndMissingRequestIdAreHandled() {
        GatewayCaller broken = caller(gateway.baseUrl(), gateway.baseUrl() + "/broken-admin");
        gateway.stubFor(any(urlPathEqualTo("/broken-admin/requests/find")).willReturn(aResponse().withStatus(500)));
        gatewayAnswers(200, Map.of("X-Request-Id", "req-4"), "ok");

        assertThat(broken.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null).upstream().checked()).isFalse();

        gatewayAnswers(200, Map.of(), "ok");
        RequestResult noId = caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null);
        assertThat(noId.upstream().checked()).isTrue();
        assertThat(noId.upstream().received()).isFalse();
    }

    @Test
    void identityProblemsAreExplained() throws Exception {
        var otherKey = TestSupport.generate("not-published");
        String forged = TestSupport.identityToken(otherKey, "acme");
        String good = TestSupport.identityToken(TestSupport.GATEWAY_KEY, "acme");
        String badSignature = good.substring(0, good.lastIndexOf('.') + 1) + forged.substring(forged.lastIndexOf('.') + 1);
        String hs256 = "eyJhbGciOiJIUzI1NiJ9.eyJ0aWQiOiJhY21lIn0.c2ln";
        Map<String, String> expectations = Map.of(
                forged, "kid not in gateway JWKS",
                badSignature, "signature mismatch",
                hs256, "unexpected algorithm HS256",
                "not-a-jwt", "not a JWT");
        int n = 0;
        for (var expectation : expectations.entrySet()) {
            String requestId = "id-" + n++;
            upstreamReceives(requestId, expectation.getKey());
            gatewayAnswers(200, Map.of("X-Request-Id", requestId), "ok");

            RequestResult result = caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null);

            assertThat(result.upstream().identity().verified()).as(expectation.getValue()).isFalse();
            assertThat(result.upstream().identity().error()).isEqualTo(expectation.getValue());
        }
    }

    @Test
    void jwksOutageIsReportedOnTheIdentity() throws Exception {
        gateway.stubFor(get("/.well-known/jwks.json").willReturn(aResponse().withStatus(503)));
        upstreamReceives("req-5", TestSupport.identityToken(TestSupport.GATEWAY_KEY, "acme"));
        gatewayAnswers(200, Map.of("X-Request-Id", "req-5"), "ok");

        RequestResult result = caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null);

        assertThat(result.upstream().identity().error()).startsWith("JWKS unavailable");
    }

    @Test
    void missingTokenFaultSendsNoAuthorizationAndCustomHeadersAndBodyAreSent() {
        gatewayAnswers(401, Map.of("Content-Type", PROBLEM, "WWW-Authenticate", "Bearer"), problem(401, "auth.missing"));
        caller.send(acme("lb", "GET", "/weather/today", TokenFault.MISSING), null, null);

        LoggedRequest sent = gateway.findAll(anyRequestedFor(urlEqualTo("/weather/today"))).getFirst();
        assertThat(sent.containsHeader("Authorization")).isFalse();

        gateway.resetRequests();
        caller.send(new RequestSpec("lb", "POST", "/weather/today", "acme", "w", "weather:write", TokenFault.NONE,
                Map.of("X-Forwarded-For", "6.6.6.6"), "{\"t\":1}"), null, null);
        gateway.verify(postRequestedFor(urlEqualTo("/weather/today"))
                .withHeader("X-Forwarded-For", equalTo("6.6.6.6"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Authorization", matching("Bearer eyJ.+")));

        gateway.resetRequests();
        caller.send(new RequestSpec("lb", "PUT", "/weather/today", "acme", "w", null, TokenFault.NONE,
                Map.of("content-type", "text/plain"), "hello"), null, null);
        assertThat(gateway.findAll(anyRequestedFor(urlEqualTo("/weather/today"))).getFirst().getHeader("Content-Type"))
                .isEqualTo("text/plain");
    }

    @Test
    void longBodiesAreTruncated() {
        gatewayAnswers(200, Map.of(), "x".repeat(5000));

        assertThat(caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null).responseBody()).hasSize(4097);
    }

    @Test
    void sequenceNumbersIncrease() {
        gatewayAnswers(200, Map.of(), "ok");

        List<Long> seqs = List.of(caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null).seq(),
                caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null).seq());

        assertThat(seqs.get(1)).isGreaterThan(seqs.get(0));
    }

    @Test
    void gatewayInstanceHeaderWinsOverTheLoadBalancersAndTheTarget() {
        gatewayAnswers(200, Map.of("X-Gateway-Instance", "gw-ed02b9ca", "X-Served-By", "gateway-2"), "ok");

        assertThat(caller.send(acme("lb", "GET", "/weather/today", TokenFault.NONE), null, null).servedBy()).isEqualTo("gw-ed02b9ca");
        assertThat(caller.send(acme("gateway-1", "GET", "/weather/today", TokenFault.NONE), null, null).servedBy())
                .isEqualTo("gw-ed02b9ca");
        assertThat(GatewayCaller.servedBy(Map.of(), "internal", false)).isEqualTo("internal");
        assertThat(GatewayCaller.servedBy(Map.of(), "lb", true)).isNull();
    }

    @Test
    void unknownTargetIsRejected() {
        assertThatThrownBy(() -> caller.send(acme("gateway-9", "GET", "/x", TokenFault.NONE), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("target");
    }
}
