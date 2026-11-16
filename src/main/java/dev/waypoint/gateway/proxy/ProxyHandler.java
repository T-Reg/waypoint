package dev.waypoint.gateway.proxy;

import dev.waypoint.gateway.error.ProblemWriter;
import dev.waypoint.gateway.error.Rejection;
import dev.waypoint.gateway.identity.IdentityTokenMinter;
import dev.waypoint.gateway.path.PathNormalizer;
import dev.waypoint.gateway.path.PathPrefix;
import dev.waypoint.gateway.pipeline.RequestContext;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.UpstreamAddressPolicy;
import dev.waypoint.gateway.routing.UpstreamSelector;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forwards an authorized request: canonical re-encoded path, cleaned headers, signed identity, streamed bodies. */
public class ProxyHandler {

    public static final String UNAVAILABLE = "upstream.unavailable";
    public static final String TIMEOUT = "upstream.timeout";
    public static final String STREAM_INTERRUPTED = "upstream.stream_interrupted";
    public static final String IDENTITY_HEADER = "X-Gateway-Identity";

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);
    private static final Pattern HOST_HEADER = Pattern.compile("[A-Za-z0-9.\\-\\[\\]:]{1,255}");

    private final UpstreamClient client;
    private final UpstreamSelector selector;
    private final UpstreamAddressPolicy addressPolicy;
    private final IdentityTokenMinter minter;
    private final ProblemWriter problems;
    private final IdleTimeoutRelay bodyRelay;
    private final long maxRequestBytes;
    private final ResponseStamp stamp;

    public ProxyHandler(UpstreamClient client, UpstreamSelector selector, UpstreamAddressPolicy addressPolicy,
                        IdentityTokenMinter minter, ProblemWriter problems, IdleTimeoutRelay bodyRelay, long maxRequestBytes,
                        ResponseStamp stamp) {
        this.client = client;
        this.selector = selector;
        this.addressPolicy = addressPolicy;
        this.minter = minter;
        this.problems = problems;
        this.bodyRelay = bodyRelay;
        this.maxRequestBytes = maxRequestBytes;
        this.stamp = stamp;
    }

    public void forward(RequestContext ctx, HttpServletResponse response) throws IOException {
        Route route = ctx.route();
        HttpServletRequest request = ctx.request();
        URI base = selector.select(route, ctx.identity());
        ctx.upstreamOrigin(base.getScheme() + "://" + base.getRawAuthority());
        URI target;
        try {
            target = targetUri(base, route, ctx.normalizedPath(), request.getQueryString());
        } catch (IllegalArgumentException e) {
            fail(ctx, response, Rejection.of(400, "request.bad_path", "request URI cannot be forwarded"));
            return;
        }
        Optional<String> blocked = addressPolicy.checkResolved(target, route.allowPrivateNetwork());
        if (blocked.isPresent()) {
            log.warn("Blocked upstream call for route {} ({}): {}", route.id(), ctx.requestId(), blocked.get());
            fail(ctx, response, Rejection.of(502, UNAVAILABLE, "upstream is unavailable"));
            return;
        }
        HttpResponse<InputStream> upstream;
        try {
            upstream = client.send(buildRequest(ctx, route, target, request));
        } catch (HttpConnectTimeoutException e) {
            fail(ctx, response, Rejection.of(502, UNAVAILABLE, "upstream is unavailable"));
            return;
        } catch (HttpTimeoutException e) {
            fail(ctx, response, Rejection.of(504, TIMEOUT, "upstream timed out"));
            return;
        } catch (IOException e) {
            if (causedByTooLarge(e)) {
                fail(ctx, response, Rejection.of(413, "request.too_large", "request body exceeds " + maxRequestBytes + " bytes"));
            } else {
                log.warn("Upstream call failed for route {} ({}): {}", route.id(), ctx.requestId(), e.toString());
                fail(ctx, response, Rejection.of(502, UNAVAILABLE, "upstream is unavailable"));
            }
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(ctx, response, Rejection.of(502, UNAVAILABLE, "upstream is unavailable"));
            return;
        }
        relay(ctx, route, upstream, response);
    }

    static URI targetUri(URI base, Route route, String canonicalPath, String rawQuery) {
        String forwardPath = route.stripPrefix() ? PathPrefix.strip(route.pathPrefix(), canonicalPath) : canonicalPath;
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        StringBuilder uri = new StringBuilder()
                .append(base.getScheme()).append("://").append(base.getRawAuthority())
                .append(basePath).append(PathNormalizer.encode(forwardPath));
        if (rawQuery != null) {
            uri.append('?').append(rawQuery);
        }
        return URI.create(uri.toString());
    }

    private HttpRequest buildRequest(RequestContext ctx, Route route, URI target, HttpServletRequest request) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(route.timeoutMs()))
                .method(ctx.method(), bodyPublisher(request));
        for (Map.Entry<String, String> header : HeaderPolicy.requestHeaders(request)) {
            builder.header(header.getKey(), header.getValue());
        }
        if (route.forwardIdentity()) {
            builder.header(IDENTITY_HEADER, minter.mint(ctx.identity(), route, ctx.requestId()));
        }
        builder.header("X-Request-Id", ctx.requestId());
        builder.header("X-Forwarded-For", String.join(", ", ctx.client().chain()));
        builder.header("X-Forwarded-Proto", forwardedProto(ctx, request));
        String host = request.getHeader("Host");
        if (host != null && HOST_HEADER.matcher(host).matches()) {
            builder.header("X-Forwarded-Host", host);
        }
        return builder.build();
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) throws IOException {
        long contentLength = request.getContentLengthLong();
        boolean chunked = request.getHeader("Transfer-Encoding") != null;
        if (contentLength <= 0 && !chunked) {
            return HttpRequest.BodyPublishers.noBody();
        }
        InputStream body = new LimitedInputStream(request.getInputStream(), maxRequestBytes);
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofInputStream(() -> body);
        return contentLength > 0 ? HttpRequest.BodyPublishers.fromPublisher(publisher, contentLength) : publisher;
    }

    private static String forwardedProto(RequestContext ctx, HttpServletRequest request) {
        String inbound = request.getHeader("X-Forwarded-Proto");
        if (ctx.client().viaTrustedProxy() && ("https".equals(inbound) || "http".equals(inbound))) {
            return inbound;
        }
        return request.getScheme();
    }

    private void relay(RequestContext ctx, Route route, HttpResponse<InputStream> upstream, HttpServletResponse response) throws IOException {
        ctx.upstreamStatus(upstream.statusCode());
        response.setStatus(upstream.statusCode());
        for (Map.Entry<String, String> header : HeaderPolicy.responseHeaders(upstream.headers())) {
            response.addHeader(header.getKey(), header.getValue());
        }
        stamp.apply(response, ctx.requestId());
        try (InputStream body = upstream.body()) {
            bodyRelay.copy(body, response.getOutputStream(), Duration.ofMillis(route.timeoutMs()));
            response.flushBuffer();
        } catch (IdleTimeoutRelay.IdleTimeoutException e) {
            log.warn("Upstream body idle on route {} ({}): {}", route.id(), ctx.requestId(), e.getMessage());
            abandon(ctx, response, Rejection.of(504, TIMEOUT, "upstream timed out"), TIMEOUT);
        } catch (IdleTimeoutRelay.UpstreamReadException e) {
            log.warn("Upstream body interrupted for {} ({})", ctx.requestId(), e.getMessage());
            abandon(ctx, response, Rejection.of(502, UNAVAILABLE, "upstream is unavailable"), STREAM_INTERRUPTED);
        } catch (IOException e) {
            log.warn("Client stream interrupted for {} ({})", ctx.requestId(), e.toString());
            ctx.error(STREAM_INTERRUPTED);
        }
    }

    private void abandon(RequestContext ctx, HttpServletResponse response, Rejection beforeCommit, String afterCommitCode)
            throws IOException {
        if (response.isCommitted()) {
            ctx.error(afterCommitCode);
            throw new ResponseAbortedException(afterCommitCode);
        }
        response.reset();
        stamp.apply(response, ctx.requestId());
        fail(ctx, response, beforeCommit);
    }

    private void fail(RequestContext ctx, HttpServletResponse response, Rejection rejection) throws IOException {
        ctx.error(rejection.code());
        problems.write(response, rejection, ctx.requestId());
    }

    private static boolean causedByTooLarge(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RequestTooLargeException) {
                return true;
            }
        }
        return false;
    }
}
