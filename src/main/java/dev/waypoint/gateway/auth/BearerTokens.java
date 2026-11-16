package dev.waypoint.gateway.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BearerTokens {

    private static final Pattern BEARER = Pattern.compile("(?i)Bearer +([A-Za-z0-9._~+/-]+=*)");

    sealed interface Extracted {
        record Token(String value) implements Extracted {}
        record Missing() implements Extracted {}
        record Malformed(String detail) implements Extracted {}
    }

    private BearerTokens() {}

    static Extracted extract(Enumeration<String> authorizationHeaders) {
        List<String> values = authorizationHeaders == null ? List.of() : new ArrayList<>(Collections.list(authorizationHeaders));
        if (values.isEmpty()) {
            return new Extracted.Missing();
        }
        if (values.size() > 1) {
            return new Extracted.Malformed("multiple Authorization headers");
        }
        Matcher matcher = BEARER.matcher(values.get(0).strip());
        return matcher.matches()
                ? new Extracted.Token(matcher.group(1))
                : new Extracted.Malformed("Authorization must use the Bearer scheme");
    }

    static Optional<String> stringClaim(Object value) {
        return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }
}
