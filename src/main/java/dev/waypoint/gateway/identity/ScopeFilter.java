package dev.waypoint.gateway.identity;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** Keeps only granted scopes that match a {@code forwardScopes} pattern: exact, or {@code ns:*}. */
public final class ScopeFilter {

    private ScopeFilter() {}

    public static String filter(Collection<String> granted, List<String> patterns) {
        return granted.stream()
                .filter(scope -> patterns.stream().anyMatch(pattern -> matches(pattern, scope)))
                .sorted()
                .collect(Collectors.joining(" "));
    }

    static boolean matches(String pattern, String scope) {
        if (pattern.endsWith(":*")) {
            return scope.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(scope);
    }
}
