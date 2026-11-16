package dev.waypoint.gateway.proxy;

import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Header hygiene (RFC 9110 §7.6.1). Hop-by-hop headers, headers named in {@code Connection},
 * credentials, client-sent {@code X-Gateway-*} and forwarding headers are removed before the
 * gateway adds its own, so {@code Connection: X-Gateway-Identity} can't strip them.
 */
public final class HeaderPolicy {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade", "http2-settings");
    private static final Set<String> STRIPPED_INBOUND = Set.of(
            "authorization", "forwarded", "x-real-ip", "x-request-id",
            // Set by the JDK client itself, which rejects them as restricted headers.
            "host", "content-length", "expect");

    private HeaderPolicy() {}

    public static List<Map.Entry<String, String>> requestHeaders(HttpServletRequest request) {
        Set<String> named = connectionTokens(request.getHeaders("Connection") == null
                ? List.of() : Collections.list(request.getHeaders("Connection")));
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (isHopByHop(lower) || named.contains(lower) || STRIPPED_INBOUND.contains(lower)
                    || lower.startsWith("x-gateway-") || lower.startsWith("x-forwarded-")) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                headers.add(Map.entry(name, value));
            }
        }
        return headers;
    }

    public static List<Map.Entry<String, String>> responseHeaders(HttpHeaders upstream) {
        Set<String> named = connectionTokens(upstream.allValues("connection"));
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        upstream.map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(":") || isHopByHop(lower) || named.contains(lower)) {
                return;
            }
            values.forEach(value -> headers.add(Map.entry(name, value)));
        });
        return headers;
    }

    static boolean isHopByHop(String lowerName) {
        return HOP_BY_HOP.contains(lowerName) || lowerName.startsWith("proxy-");
    }

    static Set<String> connectionTokens(List<String> connectionHeaders) {
        Set<String> tokens = new HashSet<>();
        for (String header : connectionHeaders) {
            for (String token : header.split(",")) {
                if (!token.isBlank()) {
                    tokens.add(token.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens;
    }
}
