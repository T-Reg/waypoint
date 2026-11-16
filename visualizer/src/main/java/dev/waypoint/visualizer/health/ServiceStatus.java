package dev.waypoint.visualizer.health;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Map;

/** One box on the architecture diagram and how healthy it looks right now. */
public record ServiceStatus(String id, Kind kind, State state, String detail, Map<String, Object> info) {

    public enum Kind {
        LOAD_BALANCER, GATEWAY, UPSTREAM, POSTGRES, VALKEY;

        @JsonValue
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum State {
        UP, DEGRADED, DOWN, UNKNOWN;

        @JsonValue
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
