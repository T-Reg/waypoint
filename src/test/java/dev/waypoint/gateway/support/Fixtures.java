package dev.waypoint.gateway.support;

import dev.waypoint.gateway.listener.ListenerPorts;
import dev.waypoint.gateway.routing.Route;
import dev.waypoint.gateway.routing.Upstream;
import java.util.List;
import java.util.Map;

public final class Fixtures {

    private Fixtures() {}

    public static Route route(String id, String prefix, Map<String, List<String>> scopes) {
        return new Route(id, prefix, List.of(new Upstream("http://127.0.0.1:9", 100)), false, scopes, null, true, id, 1000, true);
    }

    /** Listener ports that are never registered, so only explicit ports count as "own". */
    public static ListenerPorts noListeners() {
        return new ListenerPorts();
    }
}
