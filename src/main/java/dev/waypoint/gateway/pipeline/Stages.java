package dev.waypoint.gateway.pipeline;

import dev.waypoint.gateway.auth.AuthResult;
import dev.waypoint.gateway.auth.CustomerAuthenticator;
import dev.waypoint.gateway.error.Rejection;
import dev.waypoint.gateway.path.InvalidPathException;
import dev.waypoint.gateway.path.PathNormalizer;
import dev.waypoint.gateway.ratelimit.RateDecision;
import dev.waypoint.gateway.ratelimit.RateLimiter;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.RouteTable;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** The ordered policy stages (design.md §3). Authenticate and allowlist run before routing so unlisted routes stay hidden. */
public final class Stages {

    public static final String BAD_PATH = "request.bad_path";
    public static final String TOO_LARGE = "request.too_large";
    public static final String PATH_DENIED = "policy.path_denied";
    public static final String METHOD_DENIED = "policy.method_denied";
    public static final String SCOPE_MISSING = "policy.scope_missing";
    public static final String QUOTA_EXCEEDED = "policy.quota_exceeded";
    public static final String ROUTE_NOT_FOUND = "route.not_found";
    public static final String ROUTE_UNAVAILABLE = "route.unavailable";

    private Stages() {}

    public static List<Stage> ordered(long maxRequestBytes, CustomerAuthenticator authenticator, RateLimiter rateLimiter) {
        return List.of(
                bodySize(maxRequestBytes),
                normalizePath(),
                authenticate(authenticator),
                allowlist(),
                matchRoute(),
                method(),
                scopes(),
                quota(rateLimiter));
    }

    static Stage bodySize(long maxRequestBytes) {
        return ctx -> ctx.request().getContentLengthLong() > maxRequestBytes
                ? Optional.of(Rejection.of(413, TOO_LARGE, "request body exceeds " + maxRequestBytes + " bytes"))
                : Optional.empty();
    }

    static Stage normalizePath() {
        return ctx -> {
            try {
                ctx.normalizedPath(PathNormalizer.normalize(ctx.request().getRequestURI()));
                return Optional.empty();
            } catch (InvalidPathException e) {
                return Optional.of(Rejection.of(400, BAD_PATH, e.getMessage()));
            }
        };
    }

    static Stage authenticate(CustomerAuthenticator authenticator) {
        return ctx -> switch (authenticator.authenticate(ctx.request().getHeaders("Authorization"), ctx.snapshot())) {
            case AuthResult.Authenticated ok -> {
                ctx.authenticated(ok.identity(), ok.tenant());
                yield Optional.empty();
            }
            case AuthResult.Failure failure -> Optional.of(Rejection.of(401, failure.code(), failure.detail())
                    .withHeader("WWW-Authenticate", failure.code().equals(CustomerAuthenticator.MISSING)
                            ? "Bearer" : "Bearer error=\"invalid_token\""));
        };
    }

    static Stage allowlist() {
        return ctx -> ctx.tenant().allows(ctx.normalizedPath())
                ? Optional.empty()
                : Optional.of(Rejection.of(403, PATH_DENIED, "path is not in the tenant's allowlist"));
    }

    static Stage matchRoute() {
        return ctx -> switch (ctx.snapshot().routes().match(ctx.normalizedPath())) {
            case RouteTable.Match.Found found -> {
                ctx.route(found.route());
                yield Optional.empty();
            }
            case RouteTable.Match.NotFound notFound -> Optional.of(Rejection.of(404, ROUTE_NOT_FOUND, "no route matches this path"));
            case RouteTable.Match.Unavailable unavailable -> Optional.of(
                    Rejection.of(503, ROUTE_UNAVAILABLE, "the route for this path is misconfigured"));
        };
    }

    static Stage method() {
        return ctx -> {
            Route route = ctx.route();
            if (route.requiredScopesFor(ctx.method()).isPresent()) {
                return Optional.empty();
            }
            TreeSet<String> allowed = new TreeSet<>(route.configuredMethods());
            return Optional.of(Rejection.of(405, METHOD_DENIED, "method " + ctx.method() + " is not configured on this route")
                    .withHeader("Allow", String.join(", ", allowed)));
        };
    }

    static Stage scopes() {
        return ctx -> {
            List<String> required = ctx.route().requiredScopesFor(ctx.method()).orElseThrow();
            List<String> missing = required.stream().filter(s -> !ctx.identity().scopes().contains(s)).sorted().toList();
            return missing.isEmpty()
                    ? Optional.empty()
                    : Optional.of(Rejection.of(403, SCOPE_MISSING, "token lacks required scope(s): " + String.join(" ", missing)));
        };
    }

    static Stage quota(RateLimiter rateLimiter) {
        return ctx -> {
            RateDecision decision = rateLimiter.tryAcquire(ctx.tenant().id(), ctx.tenant().requestsPerMinute(), ctx.requestId());
            return decision.allowed()
                    ? Optional.empty()
                    : Optional.of(Rejection.of(429, QUOTA_EXCEEDED, "tenant request quota exceeded")
                            .withHeader("Retry-After", Long.toString(decision.retryAfterSeconds())));
        };
    }
}
