package dev.waypoint.visualizer.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.waypoint.visualizer.support.TestSupport;
import dev.waypoint.visualizer.traffic.RequestResult;
import dev.waypoint.visualizer.traffic.RequestResult.Outcome;
import dev.waypoint.visualizer.traffic.RequestResult.Upstream;
import dev.waypoint.visualizer.traffic.Stage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class EventHubTest {

    private static RequestResult result(long seq) {
        return new RequestResult(seq, null, null, TestSupport.NOW, "lb", "gateway-1", "GET", "/", "acme", "NONE", 200,
                Outcome.ALLOWED, Stage.UPSTREAM, null, null, null, null, "r", 1, Map.of(), "", Upstream.unchecked());
    }

    @Test
    void keepsOnlyTheMostRecentResults() {
        EventHub hub = new EventHub();
        for (long i = 1; i <= 310; i++) {
            hub.publishRequest(result(i));
        }

        assertThat(hub.recent()).hasSize(300);
        assertThat(hub.recent().getFirst().seq()).isEqualTo(11);
        assertThat(hub.recent().getLast().seq()).isEqualTo(310);
    }

    @Test
    void dropsSubscribersThatCanNoLongerReceive() {
        EventHub hub = new EventHub();
        SseEmitter emitter = hub.subscribe();
        hub.subscribe();
        assertThat(hub.subscriberCount()).isEqualTo(2);

        emitter.complete();
        hub.publish("health", Map.of("ok", true));

        assertThat(hub.subscriberCount()).isEqualTo(1);
    }
}
