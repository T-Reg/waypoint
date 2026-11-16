package dev.waypoint.gateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.audit.AuditEntry;
import dev.waypoint.gateway.audit.AuditRecorder;
import dev.waypoint.gateway.audit.PathHasher;
import dev.waypoint.gateway.auth.TenantIdentity;
import dev.waypoint.gateway.error.ProblemWriter;
import dev.waypoint.gateway.error.Rejection;
import dev.waypoint.gateway.identity.GatewayKeyStore;
import dev.waypoint.gateway.identity.IdentityTokenMinter;
import dev.waypoint.gateway.proxy.ClientIpResolver;
import dev.waypoint.gateway.proxy.IdleTimeoutRelay;
import dev.waypoint.gateway.proxy.ProxyHandler;
import dev.waypoint.gateway.proxy.ResponseStamp;
import dev.waypoint.gateway.proxy.UpstreamClient;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.RouteTable;
import dev.waypoint.gateway.routing.UpstreamAddressPolicy;
import dev.waypoint.gateway.routing.UpstreamSelector;
import dev.waypoint.gateway.store.ConfigSnapshot;
import dev.waypoint.gateway.store.GatewayState;
import dev.waypoint.gateway.store.SnapshotHolder;
import dev.waypoint.gateway.support.Fixtures;
import dev.waypoint.gateway.support.MutableClock;
import dev.waypoint.gateway.tenant.Tenant;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Pipeline and proxy error branches that a live upstream can't trigger deterministically. */
class GatewayPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");
    private final Route route = Fixtures.route("svc", "/svc", Map.of("*", List.of()));
    private final Tenant tenant = new Tenant("acme", List.of("/svc"), 100, false, false);
    private final List<AuditEntry> audit = new ArrayList<>();
    private final List<HttpRequest> sent = new ArrayList<>();
    private final IdleTimeoutRelay relay = new IdleTimeoutRelay();
    private ResponseStamp stamp = ResponseStamp.requestIdOnly();

    @AfterEach
    void closeRelay() {
        relay.close();
    }

    @Test
    void unexpectedExceptionIs500InternalAuditedAndNeverProxied() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        pipeline(List.of(routed(), ctx -> { throw new IllegalStateException("boom"); }), okClient(), List.of())
                .handle(request(), response);

        assertThat(response.getStatus()).isEqualTo(500);
        JsonNode problem = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(problem.path("code").asString()).isEqualTo(GatewayPipeline.INTERNAL);
        assertThat(response.getContentAsString()).doesNotContain("boom", "IllegalStateException");
        assertThat(sent).isEmpty();
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.errorCode()).isEqualTo(GatewayPipeline.INTERNAL);
            assertThat(entry.status()).isEqualTo(500);
        });
    }

    @Test
    void unexpectedExceptionIsStillAuditedWhenTheErrorResponseCannotBeWritten() {
        HttpServletResponseWrapper broken = new HttpServletResponseWrapper(new MockHttpServletResponse()) {
            @Override
            public ServletOutputStream getOutputStream() throws IOException {
                throw new IOException("client gone");
            }
        };
        pipeline(List.of(ctx -> { throw new IllegalStateException("boom"); }), okClient(), List.of())
                .handle(request(), broken);

        assertThat(audit).singleElement().satisfies(entry -> assertThat(entry.errorCode()).isEqualTo(GatewayPipeline.INTERNAL));
    }

    @Test
    void connectTimeoutIs502Unavailable() throws Exception {
        MockHttpServletResponse response = run(request -> { throw new HttpConnectTimeoutException("connect timed out"); });
        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(code(response)).isEqualTo(ProxyHandler.UNAVAILABLE);
        assertThat(audit.getFirst().errorCode()).isEqualTo(ProxyHandler.UNAVAILABLE);
    }

    @Test
    void interruptedUpstreamCallIs502AndRestoresTheInterruptFlag() throws Exception {
        try {
            MockHttpServletResponse response = run(request -> { throw new InterruptedException(); });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(response.getStatus()).isEqualTo(502);
            assertThat(code(response)).isEqualTo(ProxyHandler.UNAVAILABLE);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void forwardedProtoIsTakenFromATrustedProxyOnlyWhenItIsHttpOrHttps() {
        List<String> trusted = List.of("127.0.0.1/32");
        assertThat(forwardedHeaders(trusted, "https", "gw.example").firstValue("X-Forwarded-Proto")).contains("https");
        assertThat(forwardedHeaders(trusted, "http", "gw.example").firstValue("X-Forwarded-Proto")).contains("http");
        assertThat(forwardedHeaders(trusted, "ftp", "gw.example").firstValue("X-Forwarded-Proto")).as("bogus value").contains("http");
        assertThat(forwardedHeaders(List.of(), "https", "gw.example").firstValue("X-Forwarded-Proto")).as("untrusted client").contains("http");
    }

    @Test
    void forwardedHostIsSentOnlyForAWellFormedHostHeader() {
        assertThat(forwardedHeaders(List.of(), null, "gw.example:8080").firstValue("X-Forwarded-Host")).contains("gw.example:8080");
        assertThat(forwardedHeaders(List.of(), null, "evil.example/path").firstValue("X-Forwarded-Host")).isEmpty();
        assertThat(forwardedHeaders(List.of(), null, null).firstValue("X-Forwarded-Host")).isEmpty();
    }

    @Test
    void instanceHeaderIsOffByDefault() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        pipeline(List.of(routed()), okClient(), List.of()).handle(request(), response);

        assertThat(response.getHeader(ResponseStamp.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(response.getHeader(ResponseStamp.INSTANCE_HEADER)).isNull();
    }

    @Test
    void exposedInstanceHeaderIsOnProxiedResponsesAndOverridesTheUpstreams() {
        stamp = new ResponseStamp("gw-1234", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        pipeline(List.of(routed()), okClient(Map.of(ResponseStamp.INSTANCE_HEADER, List.of("spoofed"))), List.of())
                .handle(request(), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders(ResponseStamp.INSTANCE_HEADER)).containsExactly("gw-1234");
    }

    @Test
    void exposedInstanceHeaderIsOnRejectionsAndUpstreamFailures() {
        stamp = new ResponseStamp("gw-1234", true);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        pipeline(List.of(ctx -> Optional.of(Rejection.of(403, "policy.path_denied", "no"))),
                okClient(), List.of()).handle(request(), rejected);
        MockHttpServletResponse failed = run(request -> { throw new HttpConnectTimeoutException("connect timed out"); });

        assertThat(rejected.getStatus()).isEqualTo(403);
        assertThat(rejected.getHeader(ResponseStamp.INSTANCE_HEADER)).isEqualTo("gw-1234");
        assertThat(failed.getStatus()).isEqualTo(502);
        assertThat(failed.getHeader(ResponseStamp.INSTANCE_HEADER)).isEqualTo("gw-1234");
    }

    private HttpHeaders forwardedHeaders(List<String> trustedProxies, String forwardedProto, String host) {
        sent.clear();
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.5");
        if (forwardedProto != null) {
            request.addHeader("X-Forwarded-Proto", forwardedProto);
        }
        request.removeHeader("Host");
        if (host != null) {
            request.addHeader("Host", host);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        pipeline(List.of(routed()), okClient(), trustedProxies).handle(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        return sent.getFirst().headers();
    }

    private MockHttpServletResponse run(UpstreamClient client) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        pipeline(List.of(routed()), client, List.of()).handle(request(), response);
        return response;
    }

    private GatewayPipeline pipeline(List<Stage> stages, UpstreamClient client, List<String> trustedProxies) {
        GatewayKeyStore keys = new GatewayKeyStore(clock, Duration.ofSeconds(90));
        keys.activate(GatewayKeyStore.generate());
        ProblemWriter problems = new ProblemWriter(MAPPER);
        UpstreamClient recording = request -> {
            sent.add(request);
            return client.send(request);
        };
        ProxyHandler proxy = new ProxyHandler(recording, UpstreamSelector.primary(), new UpstreamAddressPolicy(Fixtures.noListeners()),
                new IdentityTokenMinter(keys, "waypoint-gateway", Duration.ofSeconds(60), clock), problems, relay, 1024, stamp);
        SnapshotHolder snapshots = new SnapshotHolder();
        snapshots.swapIfNewer(new ConfigSnapshot(1, RouteTable.builder().add(route).build(), Map.of("acme", tenant), List.of()));
        GatewayState state = new GatewayState();
        state.markBootstrapped();
        AuditRecorder recorder = new AuditRecorder(audit::add, new PathHasher(new byte[32]), clock);
        return new GatewayPipeline(stages, proxy, snapshots, state, new ClientIpResolver(trustedProxies), problems, recorder, stamp);
    }

    private Stage routed() {
        return ctx -> {
            ctx.normalizedPath("/svc/x");
            ctx.authenticated(new TenantIdentity("acme", "web", Set.of()), tenant);
            ctx.route(route);
            return Optional.empty();
        };
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/svc/x");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private static String code(MockHttpServletResponse response) throws IOException {
        return MAPPER.readTree(response.getContentAsByteArray()).path("code").asString();
    }

    private static UpstreamClient okClient() {
        return okClient(Map.of());
    }

    private static UpstreamClient okClient(Map<String, List<String>> headers) {
        return request -> new HttpResponse<>() {
            @Override public int statusCode() { return 200; }
            @Override public HttpRequest request() { return request; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(headers, (name, value) -> true); }
            @Override public InputStream body() { return new ByteArrayInputStream(new byte[0]); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return request.uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
