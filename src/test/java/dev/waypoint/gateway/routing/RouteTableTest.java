package dev.waypoint.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.gateway.support.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteTableTest {

    private final Map<String, List<String>> any = Map.of("*", List.of());

    @Test
    void longestSegmentPrefixWins() {
        RouteTable table = RouteTable.builder()
                .add(Fixtures.route("api", "/api", any))
                .add(Fixtures.route("v1", "/api/v1", any))
                .add(Fixtures.route("root", "/", any))
                .build();
        assertThat(found(table, "/api/v1/users")).isEqualTo("v1");
        assertThat(found(table, "/api/v10")).isEqualTo("api");
        assertThat(found(table, "/apix")).isEqualTo("root");
        assertThat(table.routes()).containsOnlyKeys("api", "v1", "root");
    }

    @Test
    void noMatch() {
        RouteTable table = RouteTable.builder().add(Fixtures.route("api", "/api", any)).build();
        assertThat(table.match("/apix")).isInstanceOf(RouteTable.Match.NotFound.class);
        assertThat(RouteTable.empty().match("/")).isInstanceOf(RouteTable.Match.NotFound.class);
    }

    @Test
    void invalidEntryFailsClosedForItsPrefixOnly() {
        RouteTable table = RouteTable.builder()
                .add(Fixtures.route("api", "/api", any))
                .addUnavailable("broken", "/api/broken")
                .build();
        assertThat(table.match("/api/broken/x")).isEqualTo(new RouteTable.Match.Unavailable("broken"));
        assertThat(found(table, "/api/fine")).isEqualTo("api");
    }

    @Test
    void duplicatePrefixPrefersFailClosed() {
        RouteTable table = RouteTable.builder()
                .add(Fixtures.route("a", "/x", any))
                .addUnavailable("b", "/x")
                .build();
        assertThat(table.match("/x")).isInstanceOf(RouteTable.Match.Unavailable.class);
    }

    private static String found(RouteTable table, String path) {
        return ((RouteTable.Match.Found) table.match(path)).route().id();
    }
}
