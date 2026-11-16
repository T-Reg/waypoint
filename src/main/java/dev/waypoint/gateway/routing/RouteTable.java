package dev.waypoint.gateway.routing;

import dev.waypoint.gateway.path.PathPrefix;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Immutable route snapshot. Invalid DB entries keep their prefix so it fails closed with 503. */
public final class RouteTable {

    public sealed interface Match {
        record Found(Route route) implements Match {}
        record NotFound() implements Match {}
        record Unavailable(String routeId) implements Match {}
    }

    private record Entry(String prefix, Route route, String invalidRouteId) {}

    private final List<Entry> entries;
    private final Map<String, Route> byId;

    private RouteTable(List<Entry> entries) {
        this.entries = entries.stream()
                .sorted(Comparator.comparingInt((Entry e) -> e.prefix().length()).reversed())
                .toList();
        this.byId = entries.stream().filter(e -> e.route() != null)
                .collect(Collectors.toUnmodifiableMap(e -> e.route().id(), Entry::route));
    }

    public static RouteTable empty() {
        return new RouteTable(List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Match match(String canonicalPath) {
        for (Entry entry : entries) {
            if (PathPrefix.matches(entry.prefix(), canonicalPath)) {
                return entry.route() != null ? new Match.Found(entry.route()) : new Match.Unavailable(entry.invalidRouteId());
            }
        }
        return new Match.NotFound();
    }

    public Map<String, Route> routes() {
        return byId;
    }

    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        public Builder add(Route route) {
            entries.add(new Entry(route.pathPrefix(), route, null));
            return this;
        }

        public Builder addUnavailable(String routeId, String prefix) {
            entries.add(new Entry(prefix, null, routeId));
            return this;
        }

        public RouteTable build() {
            Map<String, Entry> byPrefix = entries.stream()
                    .collect(Collectors.toMap(Entry::prefix, Function.identity(), (a, b) -> a.route() == null ? a : b));
            return new RouteTable(List.copyOf(byPrefix.values()));
        }
    }
}
