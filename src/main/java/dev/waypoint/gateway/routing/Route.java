package dev.waypoint.gateway.routing;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public record Route(
        String id,
        String pathPrefix,
        List<Upstream> upstreams,
        boolean stripPrefix,
        Map<String, List<String>> requiredScopes,
        List<String> forwardScopes,
        boolean forwardIdentity,
        String audience,
        int timeoutMs,
        boolean allowPrivateNetwork) {

    public static final String ANY_METHOD = "*";

    public Route {
        upstreams = List.copyOf(upstreams);
        Map<String, List<String>> copy = new LinkedHashMap<>();
        requiredScopes.forEach((method, scopes) -> copy.put(method, List.copyOf(scopes)));
        requiredScopes = Map.copyOf(copy);
        forwardScopes = forwardScopes == null ? null : List.copyOf(forwardScopes);
    }

    /** Scopes required for {@code method}, falling back to {@code *}; empty if the method isn't configured. */
    public Optional<List<String>> requiredScopesFor(String method) {
        List<String> scopes = requiredScopes.get(method);
        if (scopes == null) {
            scopes = requiredScopes.get(ANY_METHOD);
        }
        return Optional.ofNullable(scopes);
    }

    public Set<String> configuredMethods() {
        return new TreeSet<>(requiredScopes.keySet());
    }

    /** Configured {@code forwardScopes}, or {@code ns:*} for every namespace among the required scopes. */
    public List<String> effectiveForwardScopes() {
        if (forwardScopes != null) {
            return forwardScopes;
        }
        Set<String> namespaces = new TreeSet<>();
        requiredScopes.values().forEach(scopes -> scopes.forEach(scope -> {
            int colon = scope.indexOf(':');
            if (colon > 0) {
                namespaces.add(scope.substring(0, colon) + ":*");
            }
        }));
        return List.copyOf(namespaces);
    }

    public URI primaryUpstream() {
        return URI.create(upstreams.get(0).url());
    }

    public RouteSpec toSpec() {
        return new RouteSpec(pathPrefix, upstreams, stripPrefix, requiredScopes, forwardScopes,
                forwardIdentity, audience, timeoutMs, allowPrivateNetwork);
    }
}
