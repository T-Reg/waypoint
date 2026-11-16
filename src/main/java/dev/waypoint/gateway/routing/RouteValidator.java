package dev.waypoint.gateway.routing;

import dev.waypoint.gateway.path.PathPrefix;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class RouteValidator {

    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern METHOD = Pattern.compile("[A-Z]{1,20}|\\*");
    private static final Pattern SCOPE = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]{1,128}");
    private static final Pattern FORWARD_PATTERN = Pattern.compile("[\\x21\\x23-\\x29\\x2B-\\x5B\\x5D-\\x7E]{1,128}(:\\*)?");
    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int MAX_TIMEOUT_MS = 300_000;

    public enum Mode {
        /** Admin writes: resolve hostnames and check every address. */
        WRITE,
        /** Loading from the DB: no DNS, so a resolver outage can't take routes down. */
        LOAD
    }

    private final UpstreamAddressPolicy addressPolicy;

    public RouteValidator(UpstreamAddressPolicy addressPolicy) {
        this.addressPolicy = addressPolicy;
    }

    public Route validate(String id, RouteSpec spec, Mode mode) throws ValidationException {
        if (spec == null) {
            throw new ValidationException(List.of("body is required"));
        }
        List<String> errors = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) {
            errors.add("id must match " + ID.pattern());
        }
        if (!PathPrefix.isValid(spec.pathPrefix())) {
            errors.add("pathPrefix must be a canonical path such as /weather");
        }
        boolean allowPrivate = Boolean.TRUE.equals(spec.allowPrivateNetwork());
        List<Upstream> upstreams = validateUpstreams(spec.upstreams(), allowPrivate, mode, errors);
        Map<String, List<String>> requiredScopes = validateRequiredScopes(spec.requiredScopes(), errors);
        if (spec.forwardScopes() != null) {
            spec.forwardScopes().stream()
                    .filter(pattern -> pattern == null || !FORWARD_PATTERN.matcher(pattern).matches())
                    .forEach(pattern -> errors.add("invalid forwardScopes pattern: " + pattern));
        }
        int timeoutMs = spec.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : spec.timeoutMs();
        if (timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
            errors.add("timeoutMs must be between 1 and " + MAX_TIMEOUT_MS);
        }
        String audience = spec.audience() == null ? id : spec.audience();
        if (audience == null || audience.isBlank() || audience.length() > 256) {
            errors.add("audience must be 1-256 characters");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new Route(id, spec.pathPrefix(), upstreams, Boolean.TRUE.equals(spec.stripPrefix()), requiredScopes,
                spec.forwardScopes(), !Boolean.FALSE.equals(spec.forwardIdentity()), audience, timeoutMs, allowPrivate);
    }

    private List<Upstream> validateUpstreams(List<Upstream> upstreams, boolean allowPrivate, Mode mode, List<String> errors) {
        if (upstreams == null || upstreams.isEmpty()) {
            errors.add("at least one upstream is required");
            return List.of();
        }
        List<Upstream> result = new ArrayList<>();
        for (Upstream upstream : upstreams) {
            if (upstream == null || upstream.url() == null) {
                errors.add("upstream url is required");
                continue;
            }
            int weight = upstream.weight() == null ? 100 : upstream.weight();
            if (weight < 0 || weight > 100) {
                errors.add("upstream weight must be between 0 and 100");
            }
            Optional<String> problem = addressPolicy.checkSyntax(upstream.url(), allowPrivate);
            if (problem.isEmpty() && mode == Mode.WRITE) {
                problem = addressPolicy.checkResolved(URI.create(upstream.url()), allowPrivate);
            }
            problem.ifPresent(errors::add);
            result.add(new Upstream(upstream.url(), weight));
        }
        return result;
    }

    private Map<String, List<String>> validateRequiredScopes(Map<String, List<String>> requiredScopes, List<String> errors) {
        if (requiredScopes == null || requiredScopes.isEmpty()) {
            errors.add("requiredScopes must configure at least one method (use \"*\" for any)");
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        requiredScopes.forEach((method, scopes) -> {
            if (method == null || !METHOD.matcher(method).matches() || method.equals("CONNECT") || method.equals("TRACE")) {
                errors.add("invalid HTTP method in requiredScopes: " + method);
                return;
            }
            List<String> list = scopes == null ? List.of() : scopes;
            list.stream().filter(scope -> scope == null || !SCOPE.matcher(scope).matches())
                    .forEach(scope -> errors.add("invalid scope: " + scope));
            result.put(method, list);
        });
        return result;
    }
}
