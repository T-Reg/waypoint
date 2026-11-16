package dev.waypoint.visualizer.traffic;

import dev.waypoint.visualizer.config.VisualizerProperties;
import dev.waypoint.visualizer.token.TokenMinter;
import dev.waypoint.visualizer.traffic.RequestResult.Upstream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Sends one request through the gateway stack and explains the outcome. */
@Component
public class GatewayCaller implements RequestSender {

    public static final String LOAD_BALANCER_TARGET = "lb";
    static final String INSTANCE_HEADER = "X-Gateway-Instance";
    static final String SERVED_BY_HEADER = "X-Served-By";
    private static final int MAX_BODY_CHARS = 4096;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final TokenMinter tokens;
    private final UpstreamJournal journal;
    private final Map<String, String> targets;
    private final Duration timeout;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();

    public GatewayCaller(HttpClient http, ObjectMapper mapper, TokenMinter tokens, UpstreamJournal journal,
                         VisualizerProperties properties, Clock clock) {
        this.http = http;
        this.mapper = mapper;
        this.tokens = tokens;
        this.journal = journal;
        this.targets = properties.targets();
        this.timeout = properties.requestTimeout();
        this.clock = clock;
    }

    @Override
    public void checkTarget(String target) {
        if (!targets.containsKey(target)) {
            throw new IllegalArgumentException("target must be one of " + targets.keySet());
        }
    }

    @Override
    public RequestResult send(RequestSpec spec, String runId, String scenarioId) {
        checkTarget(spec.target());
        HttpRequest request = build(spec);
        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            return explain(spec, runId, scenarioId, response, latencyMs);
        } catch (IOException e) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            return unreachable(spec, runId, scenarioId, latencyMs, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreachable(spec, runId, scenarioId, 0, e);
        }
    }

    private HttpRequest build(RequestSpec spec) {
        HttpRequest.BodyPublisher body = spec.body() == null || spec.body().isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(spec.body());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targets.get(spec.target()) + spec.path()))
                .timeout(timeout)
                .method(spec.method(), body);
        spec.headers().forEach(builder::header);
        if (spec.body() != null && !spec.body().isEmpty() && spec.headers().keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
            builder.header("Content-Type", "application/json");
        }
        tokens.mint(spec.tenant(), spec.subject(), spec.scope(), spec.fault())
                .ifPresent(token -> builder.header("Authorization", "Bearer " + token));
        return builder.build();
    }

    private RequestResult explain(RequestSpec spec, String runId, String scenarioId, HttpResponse<byte[]> response, long latencyMs) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));
        String body = new String(response.body(), StandardCharsets.UTF_8);
        String requestId = headers.get("X-Request-Id");
        Problem problem = problem(headers.get("Content-Type"), body);
        boolean viaLoadBalancer = LOAD_BALANCER_TARGET.equals(spec.target());
        Upstream upstream = StageMapper.isGatewayCode(problem.code()) ? Upstream.notReceived() : journal.lookup(requestId);
        StageMapper.Placement placement = StageMapper.place(response.statusCode(), problem.code(), upstream, viaLoadBalancer);
        String servedBy = servedBy(headers, spec.target(), viaLoadBalancer);
        return new RequestResult(sequence.incrementAndGet(), runId, scenarioId, clock.instant(), spec.target(), servedBy,
                spec.method(), spec.path(), spec.tenant(), spec.fault().name(), response.statusCode(), placement.outcome(),
                placement.stage(), problem.code(), problem.detail(), headers.get("Retry-After"), headers.get("Allow"),
                requestId, latencyMs, Map.copyOf(headers), truncate(body), upstream);
    }

    /** The gateway's own X-Gateway-Instance wins; HAProxy's X-Served-By is the fallback for the local stack. */
    static String servedBy(Map<String, String> headers, String target, boolean viaLoadBalancer) {
        String instance = headers.get(INSTANCE_HEADER);
        if (instance != null) {
            return instance;
        }
        return viaLoadBalancer ? headers.get(SERVED_BY_HEADER) : target;
    }

    private RequestResult unreachable(RequestSpec spec, String runId, String scenarioId, long latencyMs, Exception e) {
        StageMapper.Placement placement = StageMapper.unreachable(LOAD_BALANCER_TARGET.equals(spec.target()));
        String detail = spec.target() + " unreachable: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return new RequestResult(sequence.incrementAndGet(), runId, scenarioId, clock.instant(), spec.target(), null,
                spec.method(), spec.path(), spec.tenant(), spec.fault().name(), 0, placement.outcome(), placement.stage(),
                null, detail, null, null, null, latencyMs, Map.of(), null, Upstream.unchecked());
    }

    private record Problem(String code, String detail) {
    }

    private Problem problem(String contentType, String body) {
        if (contentType == null || !contentType.startsWith("application/problem+json")) {
            return new Problem(null, null);
        }
        try {
            JsonNode json = mapper.readTree(body);
            return new Problem(json.path("code").asString(null), json.path("detail").asString(null));
        } catch (JacksonException e) {
            return new Problem(null, null);
        }
    }

    private static String truncate(String body) {
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS) + "…";
    }
}
