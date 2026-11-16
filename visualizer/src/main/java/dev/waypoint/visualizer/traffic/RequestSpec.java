package dev.waypoint.visualizer.traffic;

import dev.waypoint.visualizer.token.TokenFault;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** One request to send through the gateway; {@code target} names an entry in visualizer.targets. */
public record RequestSpec(
        String target,
        String method,
        String path,
        String tenant,
        String subject,
        String scope,
        TokenFault fault,
        Map<String, String> headers,
        String body) {

    static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "host", "connection", "content-length", "expect", "upgrade", "transfer-encoding");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}");
    private static final Pattern PATH = Pattern.compile("/[\\x21-\\x7E]{0,2047}");
    private static final int MAX_BODY = 16 * 1024;

    public RequestSpec {
        method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        subject = subject == null || subject.isBlank() ? "visualizer" : subject;
        fault = fault == null ? TokenFault.NONE : fault;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("method must be one of " + METHODS);
        }
        if (path == null || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("path must start with / and contain only visible ASCII (encode the rest)");
        }
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("tenant is required");
        }
        if (body != null && body.length() > MAX_BODY) {
            throw new IllegalArgumentException("body must be at most " + MAX_BODY + " characters");
        }
        headers.forEach(RequestSpec::checkHeader);
    }

    public RequestSpec withTarget(String newTarget) {
        return new RequestSpec(newTarget, method, path, tenant, subject, scope, fault, headers, body);
    }

    private static void checkHeader(String name, String value) {
        if (!HEADER_NAME.matcher(name).matches() || RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("header '" + name + "' is not allowed");
        }
        if (value == null || value.length() > 1024 || value.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new IllegalArgumentException("header '" + name + "' has an invalid value");
        }
    }
}
