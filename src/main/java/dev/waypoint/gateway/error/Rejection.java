package dev.waypoint.gateway.error;

import java.util.LinkedHashMap;
import java.util.Map;

/** A request outcome that short-circuits the pipeline; rendered as RFC 9457 problem JSON. */
public record Rejection(int status, String code, String detail, Map<String, String> headers) {

    public Rejection {
        headers = Map.copyOf(headers);
    }

    public static Rejection of(int status, String code, String detail) {
        return new Rejection(status, code, detail, Map.of());
    }

    public Rejection withHeader(String name, String value) {
        Map<String, String> next = new LinkedHashMap<>(headers);
        next.put(name, value);
        return new Rejection(status, code, detail, next);
    }
}
